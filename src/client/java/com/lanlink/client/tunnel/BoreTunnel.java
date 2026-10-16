package com.lanlink.client.tunnel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal client for the open-source bore TCP tunnel protocol
 * (https://github.com/ekzhang/bore), compatible with public relays such as bore.pub.
 *
 * On {@link #start()} it connects to the relay and asks for a random public port, then
 * accepts every incoming tunneled connection and proxies it to 127.0.0.1:&lt;localPort&gt;
 * (the Minecraft LAN server).
 *
 * Sockets are tuned for latency-sensitive game traffic (TCP_NODELAY, large buffers), the
 * relay's numeric address is cached across sessions so reconnects skip DNS, and if the relay
 * host name cannot be reached through the system resolver (for example because the ISP
 * hijacks or blocks it), the name is resolved over HTTPS via Google or Cloudflare DNS.
 */
public final class BoreTunnel {
	public interface Listener {
		void onReady(String relayHost, int publicPort, String resolvedIp);

		void onError(String message);

		void onClosed(boolean expected);
	}

	/** Lets the tunnel remember the relay's numeric address across sessions. */
	public interface IpCache {
		/** Returns the previously resolved address for {@code host}, or null. */
		String get(String host);

		/** Stores the resolved address for {@code host}. */
		void put(String host, String ip);

		/** Drops a cached address that no longer works. */
		void invalidate(String host);
	}

	private static final int CONNECT_TIMEOUT_MS = 6000;
	private static final int QUICK_CONNECT_TIMEOUT_MS = 2500;
	private static final int SOCKET_BUFFER_SIZE = 256 * 1024;
	private static final int PUMP_BUFFER_SIZE = 64 * 1024;
	private static final Pattern HELLO_PATTERN = Pattern.compile("\"Hello\"\\s*:\\s*(\\d+)");
	private static final Pattern CONNECTION_PATTERN = Pattern.compile("\"Connection\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern ERROR_PATTERN = Pattern.compile("\"Error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern IPV4_PATTERN = Pattern.compile("\"data\"\\s*:\\s*\"([0-9]{1,3}(?:\\.[0-9]{1,3}){3})\"");

	private final String relayHost;
	private final int relayPort;
	private final int localPort;
	private final Listener listener;
	private final IpCache ipCache;
	private final AtomicBoolean stopped = new AtomicBoolean();
	private final Set<Socket> connections = ConcurrentHashMap.newKeySet();

	private volatile Socket control;
	private volatile Thread thread;
	private volatile String connectHost;
	private volatile String resolvedIp;

	public BoreTunnel(String relayHost, int relayPort, int localPort, Listener listener) {
		this(relayHost, relayPort, localPort, listener, null);
	}

	public BoreTunnel(String relayHost, int relayPort, int localPort, Listener listener, IpCache ipCache) {
		this.relayHost = relayHost;
		this.relayPort = relayPort;
		this.localPort = localPort;
		this.listener = listener;
		this.ipCache = ipCache;
		this.connectHost = relayHost;
	}

	public synchronized void start() {
		if (this.thread != null) {
			throw new IllegalStateException("Tunnel already started");
		}
		Thread tunnelThread = new Thread(this::run, "LAN Link tunnel");
		tunnelThread.setDaemon(true);
		this.thread = tunnelThread;
		tunnelThread.start();
	}

	/** Closes the tunnel and all proxied connections. Safe to call from any thread. */
	public synchronized void stop() {
		this.stopped.set(true);
		closeQuietly(this.control);
		for (Socket socket : this.connections) {
			closeQuietly(socket);
		}
		Thread tunnelThread = this.thread;
		if (tunnelThread != null && tunnelThread != Thread.currentThread()) {
			tunnelThread.interrupt();
		}
	}

	private void run() {
		try {
			Socket socket = connectToRelay();
			this.control = socket;
			OutputStream out = socket.getOutputStream();
			InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);

			writeFrame(out, "{\"Hello\":0}");
			String reply = readFrame(in);
			if (reply == null) {
				throw new IOException("Relay closed the connection during the handshake");
			}
			Matcher hello = HELLO_PATTERN.matcher(reply);
			if (!hello.find()) {
				throw new IOException(describeHandshakeFailure(reply));
			}
			int publicPort = Integer.parseInt(hello.group(1));
			this.listener.onReady(this.relayHost, publicPort, this.resolvedIp);

			while (!this.stopped.get()) {
				String frame = readFrame(in);
				if (frame == null) {
					break;
				}
				if (frame.contains("Heartbeat")) {
					continue;
				}
				Matcher connection = CONNECTION_PATTERN.matcher(frame);
				if (connection.find()) {
					acceptConnection(connection.group(1));
					continue;
				}
				Matcher error = ERROR_PATTERN.matcher(frame);
				if (error.find()) {
					this.listener.onError(error.group(1));
					break;
				}
			}
		} catch (Throwable t) {
			if (!this.stopped.get()) {
				this.listener.onError(describe(t));
			}
		} finally {
			closeQuietly(this.control);
			this.listener.onClosed(this.stopped.get());
		}
	}

	private Socket connectToRelay() throws IOException {
		IOException last = null;

		// 1. The remembered address - skips DNS completely (some ISPs hijack the name).
		if (this.ipCache != null) {
			String cached = this.ipCache.get(this.relayHost);
			if (cached != null) {
				try {
					return adopt(connect(cached, CONNECT_TIMEOUT_MS));
				} catch (IOException e) {
					this.ipCache.invalidate(this.relayHost);
					last = e;
				}
			}
		}

		// 2. The system resolver (fast when it is not hijacked).
		try {
			return adopt(connect(this.connectHost, QUICK_CONNECT_TIMEOUT_MS));
		} catch (IOException e) {
			last = e;
		}

		// 3. DNS over HTTPS, then connect by address.
		String ip = resolveOverHttps(this.relayHost);
		if (ip != null) {
			try {
				return adopt(connect(ip, CONNECT_TIMEOUT_MS));
			} catch (IOException e) {
				last = e;
			}
		}
		throw last;
	}

	private Socket adopt(Socket socket) {
		String ip = socket.getInetAddress().getHostAddress();
		this.resolvedIp = ip;
		this.connectHost = ip;
		if (this.ipCache != null) {
			this.ipCache.put(this.relayHost, ip);
		}
		return socket;
	}

	private Socket connect(String host, int timeoutMs) throws IOException {
		Socket socket = new Socket();
		try {
			// Small game packets must go out immediately - never let Nagle batch them.
			socket.setTcpNoDelay(true);
			socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
			socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
			socket.connect(new InetSocketAddress(host, this.relayPort), timeoutMs);
			return socket;
		} catch (IOException e) {
			closeQuietly(socket);
			throw e;
		}
	}

	private static String resolveOverHttps(String host) {
		String[] endpoints = {
			"https://dns.google/resolve?name=" + host + "&type=A",
			"https://cloudflare-dns.com/dns-query?name=" + host + "&type=A"
		};
		HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
		for (String endpoint : endpoints) {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
					.header("accept", "application/dns-json")
					.timeout(Duration.ofSeconds(5))
					.GET()
					.build();
				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					Matcher matcher = IPV4_PATTERN.matcher(response.body());
					if (matcher.find()) {
						return matcher.group(1);
					}
				}
			} catch (Throwable ignored) {
				// try the next resolver
			}
		}
		return null;
	}

	private void acceptConnection(String id) {
		Thread proxy = new Thread(() -> proxy(id), "LAN Link proxy");
		proxy.setDaemon(true);
		proxy.start();
	}

	private void proxy(String id) {
		Socket remote;
		try {
			remote = connectToRelay();
		} catch (IOException e) {
			return; // relay unreachable, drop this connection
		}
		this.connections.add(remote);
		Socket local = null;
		try {
			writeFrame(remote.getOutputStream(), "{\"Accept\":\"" + id + "\"}");
			local = new Socket();
			local.setTcpNoDelay(true);
			local.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
			local.setSendBufferSize(SOCKET_BUFFER_SIZE);
			local.connect(new InetSocketAddress("127.0.0.1", this.localPort), CONNECT_TIMEOUT_MS);
			this.connections.add(local);
			Thread forward = pumpThread(remote, local);
			Thread backward = pumpThread(local, remote);
			join(forward);
			join(backward);
		} catch (IOException e) {
			closeQuietly(remote);
			closeQuietly(local);
		} finally {
			this.connections.remove(remote);
			this.connections.remove(local);
		}
	}

	private static Thread pumpThread(Socket from, Socket to) {
		Thread pump = new Thread(() -> {
			byte[] buffer = new byte[PUMP_BUFFER_SIZE];
			try {
				InputStream in = from.getInputStream();
				OutputStream out = to.getOutputStream();
				int read;
				while ((read = in.read(buffer)) != -1) {
					out.write(buffer, 0, read);
					out.flush();
				}
			} catch (IOException ignored) {
				// either side closed - fall through and close both
			} finally {
				closeQuietly(from);
				closeQuietly(to);
			}
		}, "LAN Link relay");
		pump.setDaemon(true);
		pump.start();
		return pump;
	}

	private static void join(Thread thread) {
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static void writeFrame(OutputStream out, String json) throws IOException {
		byte[] payload = json.getBytes(StandardCharsets.UTF_8);
		// One write, one segment - no pointless extra packets.
		out.write(Arrays.copyOf(payload, payload.length + 1));
		out.flush();
	}

	private static String readFrame(InputStream in) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		int read;
		while ((read = in.read()) != -1) {
			if (read == 0) {
				return buffer.toString(StandardCharsets.UTF_8);
			}
			buffer.write(read);
			if (buffer.size() > 4096) {
				throw new IOException("Relay frame too large");
			}
		}
		return null;
	}

	private static String describeHandshakeFailure(String reply) {
		Matcher error = ERROR_PATTERN.matcher(reply);
		if (error.find()) {
			return "Relay refused the connection: " + error.group(1);
		}
		if (reply.contains("Challenge")) {
			return "Relay requires authentication (not supported)";
		}
		return "Unexpected reply from relay: " + reply;
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
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
}
