package com.lanlink.client.tunnel;

import com.lanlink.client.LanLinkClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for the open-source QUIClime relay protocol used by the e4mc relay network
 * (https://e4mc.link - protocol and relay software are MIT-licensed by the e4mc project).
 *
 * Unlike the bore protocol, QUIClime hands out a private subdomain instead of a port on a
 * shared relay, and a broker picks the relay closest to this computer (for India that is
 * the Asia-Pacific relay, which is several times faster than bore.pub's New Jersey server).
 * Friends join with plain vanilla Minecraft: Direct Connect -> the assigned domain.
 *
 * Flow: connect over QUIC (UDP) to the relay's control port, open one bidirectional
 * "control" stream and ask for a domain assignment. Afterwards the relay opens one
 * bidirectional stream per player connection; each is proxied to 127.0.0.1:&lt;localPort&gt;
 * (the Minecraft LAN server). The relay re-injects the parsed Minecraft handshake into
 * the stream, so the local server receives a complete connection.
 */
public final class QuiclimeTunnel {
	public interface Listener {
		/** Called once the relay assigned the public domain (e.g. {@code word-word.ap.e4mc.link}). */
		void onReady(String domain);

		void onError(String message);

		void onClosed(boolean expected);
	}

	private static final String BROKER_URL = "https://broker.e4mc.link/getBestRelay";
	private static final String DEFAULT_RELAY_HOST = "ap.e4mc.link";
	private static final int DEFAULT_RELAY_PORT = 25575;
	private static final Pattern JSON_HOST = Pattern.compile("\"host\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern JSON_PORT = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
	private static final Pattern JSON_DOMAIN = Pattern.compile("\"domain\"\\s*:\\s*\"([^\"]+)\"");
	private static final int CONNECT_TIMEOUT_MS = 10000;
	private static final int DOMAIN_TIMEOUT_MS = 20000;
	private static final int LOCAL_CONNECT_TIMEOUT_MS = 6000;
	private static final int SOCKET_BUFFER_SIZE = 256 * 1024;
	private static final int PUMP_BUFFER_SIZE = 64 * 1024;

	private final int localPort;
	private final Listener listener;
	private final AtomicBoolean stopped = new AtomicBoolean();
	private final Set<Socket> connections = ConcurrentHashMap.newKeySet();

	private volatile Thread thread;
	private volatile MultiThreadIoEventLoopGroup group;
	private volatile DatagramChannel datagramChannel;
	private volatile QuicChannel quicChannel;
	private volatile QuicStreamChannel controlStream;
	private volatile String relayHost = DEFAULT_RELAY_HOST;
	private volatile int relayPort = DEFAULT_RELAY_PORT;
	private volatile String domain;

	public QuiclimeTunnel(int localPort, Listener listener) {
		this.localPort = localPort;
		this.listener = listener;
	}

	public synchronized void start() {
		if (this.thread != null) {
			throw new IllegalStateException("Tunnel already started");
		}
		Thread tunnelThread = new Thread(this::run, "LAN Link relay");
		tunnelThread.setDaemon(true);
		this.thread = tunnelThread;
		tunnelThread.start();
	}

	/** Closes the tunnel and all proxied connections. Safe to call from any thread. */
	public synchronized void stop() {
		this.stopped.set(true);
		closeChannel(this.controlStream);
		closeChannel(this.quicChannel);
		closeChannel(this.datagramChannel);
		for (Socket socket : this.connections) {
			closeQuietly(socket);
		}
		Thread tunnelThread = this.thread;
		if (tunnelThread != null && tunnelThread != Thread.currentThread()) {
			tunnelThread.interrupt();
		}
	}

	/** The relay domain currently assigned to this tunnel, or null while connecting. */
	public String getDomain() {
		return this.domain;
	}

	private void run() {
		try {
			if (!this.stopped.get()) {
				resolveRelay();
			}
			if (!this.stopped.get()) {
				startQuic();
			}
			QuicChannel channel = this.quicChannel;
			if (channel != null && !this.stopped.get()) {
				channel.closeFuture().sync();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			if (!this.stopped.get()) {
				this.listener.onError(describe(t));
			}
		} finally {
			cleanup();
			this.listener.onClosed(this.stopped.get());
		}
	}

	/** Asks the e4mc broker which relay is closest; quietly keeps the default on failure. */
	private void resolveRelay() {
		try {
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.build();
			HttpRequest request = HttpRequest.newBuilder(URI.create(BROKER_URL))
				.header("accept", "application/json")
				.timeout(Duration.ofSeconds(6))
				.GET()
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return;
			}
			Matcher host = JSON_HOST.matcher(response.body());
			if (!host.find()) {
				return;
			}
			String hostName = host.group(1);
			if (hostName == null || hostName.isBlank()) {
				return;
			}
			this.relayHost = hostName;
			Matcher port = JSON_PORT.matcher(response.body());
			if (port.find()) {
				try {
					this.relayPort = Integer.parseInt(port.group(1));
				} catch (NumberFormatException ignored) {
					// keep the default port
				}
			}
			LanLinkClient.LOGGER.info("Relay broker picked {}:{}", this.relayHost, this.relayPort);
		} catch (Throwable ignored) {
			// The broker is optional - the default relay above is used instead.
		}
	}

	private void startQuic() throws IOException, InterruptedException {
		this.group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

		QuicSslContext sslContext = QuicSslContextBuilder.forClient()
			.applicationProtocols("quiclime")
			.build();

		var codec = new QuicClientCodecBuilder()
			.sslContext(sslContext)
			.sslEngineProvider(builder -> sslContext.newEngine(builder.alloc(), this.relayHost, this.relayPort))
			.initialMaxStreamsBidirectional(512)
			.maxIdleTimeout(30, TimeUnit.SECONDS)
			.initialMaxData(4611686018427387903L)
			.initialMaxStreamDataBidirectionalRemote(1250000)
			.initialMaxStreamDataBidirectionalLocal(1250000)
			.initialMaxStreamDataUnidirectional(1250000)
			.build();

		this.datagramChannel = (DatagramChannel) new Bootstrap()
			.group(this.group)
			.channel(NioDatagramChannel.class)
			.handler(codec)
			.bind(0)
			.sync().channel();
		if (this.stopped.get()) {
			return;
		}

		ChannelHandler playerStreamHandler = new ChannelInitializer<Channel>() {
			@Override
			protected void initChannel(Channel channel) {
				installStreamProxy(channel);
			}
		};

		var connectFuture = QuicChannel.newBootstrap(this.datagramChannel)
			.streamHandler(playerStreamHandler)
			.handler(new ChannelInboundHandlerAdapter())
			.remoteAddress(new InetSocketAddress(InetAddress.getByName(this.relayHost), this.relayPort))
			.connect();
		if (!connectFuture.await(CONNECT_TIMEOUT_MS)) {
			throw new IOException("Timed out connecting to the relay");
		}
		if (!connectFuture.isSuccess()) {
			throw new IOException("Could not reach the relay: " + describe(connectFuture.cause()));
		}
		this.quicChannel = (QuicChannel) connectFuture.getNow();
		if (this.stopped.get()) {
			return;
		}

		var controlFuture = this.quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, new ChannelInitializer<QuicStreamChannel>() {
			@Override
			protected void initChannel(QuicStreamChannel channel) {
				channel.pipeline().addLast(new JsonFrameCodec());
				channel.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
					@Override
					protected void channelRead0(ChannelHandlerContext ctx, String message) {
						if (!message.contains("domain_assignment_complete")) {
							return;
						}
						Matcher matcher = JSON_DOMAIN.matcher(message);
						if (matcher.find()) {
							QuiclimeTunnel.this.domain = matcher.group(1);
						}
					}
				});
			}
		});
		if (!controlFuture.await(CONNECT_TIMEOUT_MS)) {
			throw new IOException("Timed out opening the relay control channel");
		}
		this.controlStream = (QuicStreamChannel) controlFuture.getNow();
		this.controlStream.writeAndFlush(controlMessage("probe_capabilities"));
		this.controlStream.writeAndFlush(controlMessage("request_domain_assignment"));

		long deadline = System.currentTimeMillis() + DOMAIN_TIMEOUT_MS;
		while (this.domain == null && System.currentTimeMillis() < deadline && !this.stopped.get()) {
			QuicChannel channel = this.quicChannel;
			if (channel != null && !channel.isActive()) {
				throw new IOException("The relay closed the connection during setup");
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		if (this.stopped.get()) {
			return;
		}
		if (this.domain == null) {
			throw new IOException("The relay did not assign an address in time");
		}
		LanLinkClient.LOGGER.info("Relay assigned the domain {}", this.domain);
		this.listener.onReady(this.domain);
	}

	/** Proxies one relay-opened stream to the local Minecraft LAN server. */
	private void installStreamProxy(Channel channel) {
		channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
			private Socket local;

			@Override
			public void channelActive(ChannelHandlerContext ctx) {
				Socket socket = new Socket();
				try {
					socket.setTcpNoDelay(true);
					socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
					socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
					socket.connect(new InetSocketAddress("127.0.0.1", QuiclimeTunnel.this.localPort), LOCAL_CONNECT_TIMEOUT_MS);
				} catch (IOException e) {
					closeQuietly(socket);
					ctx.close();
					return;
				}
				this.local = socket;
				QuiclimeTunnel.this.connections.add(socket);
				pumpSocketToChannel(ctx.channel(), socket);
			}

			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) {
				ByteBuf buf = (ByteBuf) msg;
				try {
					Socket socket = this.local;
					if (socket == null || socket.isClosed()) {
						ctx.close();
						return;
					}
					byte[] bytes = new byte[buf.readableBytes()];
					buf.readBytes(bytes);
					OutputStream out = socket.getOutputStream();
					out.write(bytes);
					out.flush();
				} catch (IOException e) {
					ctx.close();
				} finally {
					buf.release();
				}
			}

			@Override
			public void channelInactive(ChannelHandlerContext ctx) {
				closeLocal();
			}

			@Override
			public void handlerRemoved(ChannelHandlerContext ctx) {
				closeLocal();
			}

			private void closeLocal() {
				Socket socket = this.local;
				this.local = null;
				if (socket != null) {
					QuiclimeTunnel.this.connections.remove(socket);
					closeQuietly(socket);
				}
			}
		});
	}

	private void pumpSocketToChannel(Channel channel, Socket socket) {
		Thread pump = new Thread(() -> {
			byte[] buffer = new byte[PUMP_BUFFER_SIZE];
			try {
				InputStream in = socket.getInputStream();
				int read;
				while ((read = in.read(buffer)) != -1) {
					if (!channel.isActive()) {
						break;
					}
					channel.writeAndFlush(Unpooled.wrappedBuffer(Arrays.copyOf(buffer, read)));
				}
			} catch (IOException ignored) {
				// either side closed - fall through and close both
			} finally {
				closeQuietly(socket);
				channel.close();
			}
		}, "LAN Link relay pump");
		pump.setDaemon(true);
		pump.start();
	}

	private ByteBuf controlMessage(String kind) {
		byte[] payload = ("{\"kind\":\"" + kind + "\"}").getBytes(StandardCharsets.UTF_8);
		ByteBuf buffer = Unpooled.buffer();
		int value = payload.length;
		while ((value & ~0x7F) != 0) {
			buffer.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		buffer.writeByte(value);
		buffer.writeBytes(payload);
		return buffer;
	}

	private void cleanup() {
		closeChannel(this.controlStream);
		this.controlStream = null;
		closeChannel(this.quicChannel);
		this.quicChannel = null;
		closeChannel(this.datagramChannel);
		this.datagramChannel = null;
		MultiThreadIoEventLoopGroup eventLoopGroup = this.group;
		this.group = null;
		if (eventLoopGroup != null) {
			eventLoopGroup.shutdownGracefully(0, 500, TimeUnit.MILLISECONDS);
		}
		for (Socket socket : this.connections) {
			closeQuietly(socket);
		}
		this.connections.clear();
	}

	/** Reads length-prefixed (VarInt) JSON control messages. */
	private static final class JsonFrameCodec extends ByteToMessageDecoder {
		@Override
		protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
			in.markReaderIndex();
			int value = 0;
			int shift = 0;
			int count = 0;
			while (true) {
				if (!in.isReadable()) {
					in.resetReaderIndex();
					return;
				}
				byte b = in.readByte();
				value |= (b & 0x7F) << shift;
				shift += 7;
				if (++count > 5) {
					throw new IllegalStateException("Control message length is malformed");
				}
				if ((b & 0x80) == 0) {
					break;
				}
			}
			if (in.readableBytes() < value) {
				in.resetReaderIndex();
				return;
			}
			byte[] data = new byte[value];
			in.readBytes(data);
			out.add(new String(data, StandardCharsets.UTF_8));
		}
	}

	private static void closeChannel(Channel channel) {
		if (channel == null || !channel.isOpen()) {
			return;
		}
		try {
			channel.close();
		} catch (Throwable ignored) {
			// event loop may already be shut down
		}
	}

	private static void closeQuietly(Socket socket) {
		if (socket != null) {
			try {
				socket.close();
			} catch (IOException ignored) {
				// nothing to do
			}
		}
	}

	private static String describe(Throwable t) {
		if (t == null) {
			return "unknown error";
		}
		Throwable cause = t.getCause();
		String message = t.getMessage();
		if ((message == null || message.isBlank()) && cause != null) {
			message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
		}
		return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
	}
}
