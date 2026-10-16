package com.lanlink.client;

import com.lanlink.client.tunnel.BoreTunnel;
import com.lanlink.client.tunnel.QuiclimeTunnel;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Shared state for the currently published LAN world and its public tunnel.
 */
public final class LanLink {
	public enum Status {
		IDLE,
		CONNECTING,
		ACTIVE,
		FAILED
	}

	private static volatile int maxPlayers = 8;
	private static volatile boolean publicSharingPreferred = true;
	private static volatile Status status = Status.IDLE;
	private static volatile String publicAddress = null;
	private static volatile String publicIpAddress = null;
	private static volatile String lastError = null;
	private static volatile Session currentSession = null;

	private LanLink() {
	}

	public static int getMaxPlayers() {
		return maxPlayers;
	}

	public static void setMaxPlayers(int value) {
		maxPlayers = Math.max(1, Math.min(255, value));
	}

	public static boolean isPublicSharingPreferred() {
		return publicSharingPreferred;
	}

	public static void setPublicSharingPreferred(boolean value) {
		publicSharingPreferred = value;
	}

	public static Status getStatus() {
		return status;
	}

	public static String getPublicAddress() {
		return publicAddress;
	}

	public static String getPublicIpAddress() {
		return publicIpAddress;
	}

	public static String getLastError() {
		return lastError;
	}

	/** Starts a tunnel to the given local port, unless one is already running for it. */
	public static synchronized void ensureTunnel(int localPort) {
		Session session = currentSession;
		if (session != null && session.localPort == localPort && (status == Status.CONNECTING || status == Status.ACTIVE)) {
			return;
		}
		startTunnel(localPort);
	}

	public static synchronized void startTunnel(int localPort) {
		stopTunnel();
		LanLinkConfig config = LanLinkConfig.get();
		Session session = new Session(localPort);
		currentSession = session;
		status = Status.CONNECTING;
		lastError = null;
		publicAddress = null;
		publicIpAddress = null;
		if (config.useQuiclime) {
			// Primary path: the QUIClime relay network (low-latency relay chosen by location).
			// BoreTunnel is only started if this fails - see Session.onClosed.
			session.quiclime = new QuiclimeTunnel(localPort, session);
			session.quiclime.start();
		} else {
			// The user prefers the classic relay as the primary path.
			session.startBoreFallback();
		}
	}

	public static synchronized void ensureNoTunnel() {
		if (currentSession != null) {
			stopTunnel();
		}
	}

	public static synchronized void stopTunnel() {
		Session session = currentSession;
		currentSession = null;
		if (session != null) {
			if (session.quiclime != null) {
				session.quiclime.stop();
			}
			if (session.bore != null) {
				session.bore.stop();
			}
		}
		status = Status.IDLE;
		publicAddress = null;
		publicIpAddress = null;
	}

	private static final class Session implements QuiclimeTunnel.Listener {
		final int localPort;
		volatile QuiclimeTunnel quiclime;
		volatile BoreTunnel bore;
		volatile boolean ready;

		Session(int localPort) {
			this.localPort = localPort;
		}

		// ---- Primary relay: the QUIClime relay network ----

		@Override
		public void onReady(String domain) {
			if (currentSession != this) {
				return;
			}
			this.ready = true;
			status = Status.ACTIVE;
			publicAddress = domain;
			publicIpAddress = null;
			LanLinkClient.LOGGER.info("Public address ready: {} (relay domain)", publicAddress);
			broadcastAddress(this.localPort);
		}

		@Override
		public void onError(String message) {
			if (currentSession != this) {
				return;
			}
			lastError = message;
			LanLinkClient.LOGGER.warn("Tunnel error: {}", message);
		}

		@Override
		public void onClosed(boolean expected) {
			if (currentSession != this) {
				return;
			}
			if (expected) {
				currentSession = null;
				publicAddress = null;
				publicIpAddress = null;
				status = Status.IDLE;
				return;
			}
			if (this.ready) {
				currentSession = null;
				publicAddress = null;
				publicIpAddress = null;
				status = Status.IDLE;
				notifyHost(Component.translatableWithFallback("lanlink.tunnel_lost", "Public sharing was interrupted: %s", failureMessage()));
				return;
			}
			// The primary relay could not be reached - fall back to the classic relay.
			startBoreFallback();
		}

		private void startBoreFallback() {
			if (this.bore != null || this.ready || currentSession != this) {
				return;
			}
			LanLinkConfig config = LanLinkConfig.get();
			LanLinkClient.LOGGER.info("Trying the backup relay {}:{}", config.relayHost, config.relayPort);
			BoreTunnel tunnel = new BoreTunnel(config.relayHost, config.relayPort, this.localPort, new BoreTunnel.Listener() {
				@Override
				public void onReady(String relayHost, int publicPort, String resolvedIp) {
					if (currentSession != Session.this) {
						return;
					}
					Session.this.ready = true;
					status = Status.ACTIVE;
					publicAddress = relayHost + ":" + publicPort;
					publicIpAddress = resolvedIp == null || resolvedIp.isBlank() ? null : resolvedIp + ":" + publicPort;
					LanLinkClient.LOGGER.info("Public address ready (backup relay): {} (ip form: {})", publicAddress, publicIpAddress);
					broadcastAddress(Session.this.localPort);
				}

				@Override
				public void onError(String message) {
					if (currentSession != Session.this) {
						return;
					}
					lastError = message;
					LanLinkClient.LOGGER.warn("Tunnel error: {}", message);
				}

				@Override
				public void onClosed(boolean expected) {
					if (currentSession != Session.this) {
						return;
					}
					currentSession = null;
					publicAddress = null;
					publicIpAddress = null;
					if (expected) {
						status = Status.IDLE;
						return;
					}
					if (Session.this.ready) {
						status = Status.IDLE;
						notifyHost(Component.translatableWithFallback("lanlink.tunnel_lost", "Public sharing was interrupted: %s", failureMessage()));
					} else {
						status = Status.FAILED;
						notifyHost(Component.translatableWithFallback("lanlink.tunnel_failed", "Could not get a public address: %s - friends on your Wi-Fi can still join via LAN.", failureMessage()));
					}
				}
			}, new ConfigIpCache());
			this.bore = tunnel;
			tunnel.start();
		}

		private String failureMessage() {
			String message = lastError;
			return message == null || message.isBlank() ? "the relay connection closed" : message;
		}
	}

	private static void broadcastAddress(int lanPort) {
		String address = publicAddress;
		if (address == null) {
			return;
		}
		Component message = buildAddressMessage(address, publicIpAddress, lanPort);
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			try {
				server.execute(() -> {
					try {
						server.getPlayerList().broadcastSystemMessage(message, false);
					} catch (Throwable t) {
						notifyHost(message);
					}
				});
				return;
			} catch (Throwable ignored) {
				// fall through to host-only message
			}
		}
		notifyHost(message);
	}

	private static Component buildAddressMessage(String address, String ipAddress, int lanPort) {
		Component copyHint = Component.translatableWithFallback("lanlink.copy.hover", "Click to copy");
		boolean hasIp = ipAddress != null && !ipAddress.isBlank();
		// The numeric address always works, even on networks that block the relay's DNS.
		MutableComponent message = Component.translatableWithFallback(
			"lanlink.published",
			"World opened to the internet! Share this address - click it to copy: %s",
			styledAddress(hasIp ? ipAddress : address, ChatFormatting.GREEN, copyHint));
		String ipv6 = publicIpv6Address();
		if (ipv6 != null && lanPort > 0) {
			message = message.append("\n").append(Component.translatableWithFallback(
				"lanlink.published.ipv6",
				"Direct IPv6 address (fastest - use if your internet has IPv6): %s",
				styledAddress("[" + ipv6 + "]:" + lanPort, ChatFormatting.AQUA, copyHint)));
		}
		String lanIp = localLanAddress();
		if (lanIp != null && lanPort > 0) {
			message = message.append("\n").append(Component.translatableWithFallback(
				"lanlink.published.lan",
				"Same Wi-Fi friends: this direct address is instant: %s",
				styledAddress(lanIp + ":" + lanPort, ChatFormatting.GRAY, copyHint)));
		}
		String tailscaleIp = tailscaleAddress();
		if (tailscaleIp != null && lanPort > 0) {
			message = message.append("\n").append(Component.translatableWithFallback(
				"lanlink.published.tailscale",
				"Friends with Tailscale: use this address for fast ping: %s",
				styledAddress(tailscaleIp + ":" + lanPort, ChatFormatting.AQUA, copyHint)));
		}
		if (hasIp) {
			message = message.append("\n").append(Component.translatableWithFallback(
				"lanlink.published.backup",
				"Alternative address (only works if your network can resolve it): %s",
				styledAddress(address, ChatFormatting.GRAY, copyHint)));
		}
		return message;
	}

	/** The address other devices on the local network can use to reach this computer. */
	private static String localLanAddress() {
		try (DatagramSocket socket = new DatagramSocket()) {
			// Asks the OS which local interface would be used to reach the internet.
			socket.connect(InetAddress.getByName("8.8.8.8"), 53);
			String candidate = socket.getLocalAddress().getHostAddress();
			if (candidate != null && candidate.indexOf(':') < 0) {
				return candidate;
			}
		} catch (Throwable ignored) {
			// fall through to the interface scan
		}
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces != null && interfaces.hasMoreElements()) {
				NetworkInterface networkInterface = interfaces.nextElement();
				if (!networkInterface.isUp() || networkInterface.isLoopback()) {
					continue;
				}
				for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
					InetAddress address = interfaceAddress.getAddress();
					if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
						return address.getHostAddress();
					}
				}
			}
		} catch (Throwable ignored) {
			// no local address available
		}
		return null;
	}

	/** The Tailscale address (100.x.y.z) other devices on the same tailnet can reach directly. */
	private static String tailscaleAddress() {
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces != null && interfaces.hasMoreElements()) {
				NetworkInterface networkInterface = interfaces.nextElement();
				if (!networkInterface.isUp()) {
					continue;
				}
				String name = (networkInterface.getName() + " " + networkInterface.getDisplayName()).toLowerCase();
				if (!name.contains("tailscale")) {
					continue;
				}
				for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
					InetAddress address = interfaceAddress.getAddress();
					if (address instanceof Inet4Address && isInTailscaleRange(address)) {
						return address.getHostAddress();
					}
				}
			}
		} catch (Throwable ignored) {
			// no Tailscale network in use
		}
		return null;
	}

	/** True when the address is inside Tailscale's CGNAT range (100.64.0.0/10). */
	private static boolean isInTailscaleRange(InetAddress address) {
		byte[] bytes = address.getAddress();
		int first = bytes[0] & 0xFF;
		int second = bytes[1] & 0xFF;
		return first == 100 && second >= 64 && second <= 127;
	}

	/** The computer's public IPv6 address, when the internet connection provides one. */
	private static String publicIpv6Address() {
		try (DatagramSocket socket = new DatagramSocket()) {
			// Asks the OS which IPv6 source address it would use to reach the internet.
			socket.connect(InetAddress.getByName("2001:4860:4860::8888"), 53);
			InetAddress local = socket.getLocalAddress();
			if (local instanceof Inet6Address && isPublicIpv6(local)) {
				return local.getHostAddress();
			}
		} catch (Throwable ignored) {
			// no IPv6 route
		}
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces != null && interfaces.hasMoreElements()) {
				NetworkInterface networkInterface = interfaces.nextElement();
				if (!networkInterface.isUp() || networkInterface.isLoopback()) {
					continue;
				}
				for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
					InetAddress address = interfaceAddress.getAddress();
					if (address instanceof Inet6Address && isPublicIpv6(address)) {
						return address.getHostAddress();
					}
				}
			}
		} catch (Throwable ignored) {
			// no public IPv6 address
		}
		return null;
	}

	/** True when the address is publicly routable IPv6 (2000::/3, not ULA/link-local). */
	private static boolean isPublicIpv6(InetAddress address) {
		byte[] bytes = address.getAddress();
		if (bytes.length != 16) {
			return false;
		}
		if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
			return false;
		}
		return (bytes[0] & 0xE0) == 0x20;
	}

	private static Component styledAddress(String address, ChatFormatting color, Component hint) {
		return Component.literal(address)
			.withStyle(Style.EMPTY
				.withColor(color)
				.withBold(true)
				.withClickEvent(new ClickEvent.CopyToClipboard(address))
				.withHoverEvent(new HoverEvent.ShowText(hint)));
	}

	/** Persists the relay's numeric address in the config file. */
	private static final class ConfigIpCache implements BoreTunnel.IpCache {
		@Override
		public String get(String host) {
			return LanLinkConfig.get().getCachedIp(host);
		}

		@Override
		public void put(String host, String ip) {
			LanLinkConfig config = LanLinkConfig.get();
			if (!ip.equals(config.getCachedIp(host))) {
				config.putCachedIp(host, ip);
			}
		}

		@Override
		public void invalidate(String host) {
			LanLinkConfig.get().clearCachedIp(host);
		}
	}

	private static void notifyHost(Component message) {
		Minecraft.getInstance().execute(() -> {
			try {
				Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
			} catch (Throwable ignored) {
				// client is shutting down
			}
		});
	}
}
