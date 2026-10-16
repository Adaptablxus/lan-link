package com.lanlink.client.tunnel;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, dependency-free UPnP IGD client. It asks the router (SSDP discovery plus SOAP
 * calls) to forward one TCP port to this computer, so friends can connect straight to the
 * host's public address - no relay in between, which means much lower latency for players
 * near the host.
 */
public final class UpnpPortMapper {
	private static final String SSDP_HOST = "239.255.255.250";
	private static final int SSDP_PORT = 1900;
	private static final String SERVICE_WAN_IP = "urn:schemas-upnp-org:service:WANIPConnection:1";
	private static final String SERVICE_WAN_PPP = "urn:schemas-upnp-org:service:WANPPPConnection:1";
	private static final Pattern LOCATION_PATTERN = Pattern.compile("LOCATION:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SERVICE_PATTERN = Pattern.compile("<service>(.*?)</service>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern SERVICE_TYPE_PATTERN = Pattern.compile("<serviceType>\\s*([^<\\s]+)\\s*</serviceType>", Pattern.CASE_INSENSITIVE);
	private static final Pattern CONTROL_URL_PATTERN = Pattern.compile("<controlURL>\\s*([^<\\s]+)\\s*</controlURL>", Pattern.CASE_INSENSITIVE);
	private static final Pattern EXTERNAL_IP_PATTERN = Pattern.compile("<NewExternalIPAddress>\\s*([^<\\s]+)\\s*</NewExternalIPAddress>", Pattern.CASE_INSENSITIVE);
	private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(3))
		.version(HttpClient.Version.HTTP_1_1)
		.build();

	private final String controlUrl;
	private final String serviceType;

	private UpnpPortMapper(String controlUrl, String serviceType) {
		this.controlUrl = controlUrl;
		this.serviceType = serviceType;
	}

	/** Discovers the router's UPnP port-mapping service, or returns null when unavailable. */
	public static UpnpPortMapper discover() {
		Set<URI> locations = new LinkedHashSet<>();
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.setSoTimeout(800);
			InetAddress group = InetAddress.getByName(SSDP_HOST);
			sendSearch(socket, group, "urn:schemas-upnp-org:device:InternetGatewayDevice:1");
			sendSearch(socket, group, "urn:schemas-upnp-org:service:WANIPConnection:1");
			sendSearch(socket, group, "urn:schemas-upnp-org:service:WANPPPConnection:1");
			long deadline = System.currentTimeMillis() + 2500;
			while (System.currentTimeMillis() < deadline) {
				byte[] buffer = new byte[4096];
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				try {
					socket.receive(packet);
				} catch (Exception timeout) {
					continue;
				}
				String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
				Matcher location = LOCATION_PATTERN.matcher(response);
				if (location.find()) {
					try {
						locations.add(URI.create(location.group(1).trim()));
					} catch (Throwable ignored) {
						// malformed location, skip
					}
				}
			}
		} catch (Throwable ignored) {
			// no multicast on this network
		}
		for (URI location : locations) {
			UpnpPortMapper mapper = fromDescription(location);
			if (mapper != null) {
				return mapper;
			}
		}
		return null;
	}

	private static void sendSearch(DatagramSocket socket, InetAddress group, String searchTarget) throws Exception {
		String request = "M-SEARCH * HTTP/1.1\r\n"
			+ "HOST: " + SSDP_HOST + ":" + SSDP_PORT + "\r\n"
			+ "MAN: \"ssdp:discover\"\r\n"
			+ "MX: 2\r\n"
			+ "ST: " + searchTarget + "\r\n\r\n";
		byte[] payload = request.getBytes(StandardCharsets.US_ASCII);
		socket.send(new DatagramPacket(payload, payload.length, group, SSDP_PORT));
	}

	private static UpnpPortMapper fromDescription(URI location) {
		try {
			HttpResponse<String> response = HTTP.send(
				HttpRequest.newBuilder(location).timeout(Duration.ofSeconds(4)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body() == null) {
				return null;
			}
			String wanIpControl = null;
			String wanPppControl = null;
			Matcher services = SERVICE_PATTERN.matcher(response.body());
			while (services.find()) {
				String block = services.group(1);
				Matcher type = SERVICE_TYPE_PATTERN.matcher(block);
				Matcher control = CONTROL_URL_PATTERN.matcher(block);
				if (!type.find() || !control.find()) {
					continue;
				}
				String resolved = location.resolve(control.group(1).trim()).toString();
				String serviceType = type.group(1).trim();
				if (SERVICE_WAN_IP.equalsIgnoreCase(serviceType)) {
					wanIpControl = resolved;
				} else if (SERVICE_WAN_PPP.equalsIgnoreCase(serviceType)) {
					wanPppControl = resolved;
				}
			}
			if (wanIpControl != null) {
				return new UpnpPortMapper(wanIpControl, SERVICE_WAN_IP);
			}
			if (wanPppControl != null) {
				return new UpnpPortMapper(wanPppControl, SERVICE_WAN_PPP);
			}
		} catch (Throwable ignored) {
			// description unreachable or unparseable
		}
		return null;
	}

	/** The router's own view of the internet-facing address, or null. */
	public String getExternalIpAddress() {
		String response = call("GetExternalIPAddress", "");
		if (response == null) {
			return null;
		}
		Matcher matcher = EXTERNAL_IP_PATTERN.matcher(response);
		return matcher.find() ? matcher.group(1).trim() : null;
	}

	/** Asks the router to forward the given TCP port to {@code internalClient}. */
	public boolean addTcpMapping(int port, String internalClient, String description) {
		String body = "<NewRemoteHost></NewRemoteHost>"
			+ "<NewExternalPort>" + port + "</NewExternalPort>"
			+ "<NewProtocol>TCP</NewProtocol>"
			+ "<NewInternalPort>" + port + "</NewInternalPort>"
			+ "<NewInternalClient>" + xmlEscape(internalClient) + "</NewInternalClient>"
			+ "<NewEnabled>1</NewEnabled>"
			+ "<NewPortMappingDescription>" + xmlEscape(description) + "</NewPortMappingDescription>"
			+ "<NewLeaseDuration>0</NewLeaseDuration>";
		if (call("AddPortMapping", body) != null) {
			return true;
		}
		// A stale mapping may occupy this port - clear it and try once more.
		call("DeletePortMapping", deleteBody(port));
		return call("AddPortMapping", body) != null;
	}

	/** Removes a mapping previously created with {@link #addTcpMapping}. */
	public void deleteTcpMapping(int port) {
		call("DeletePortMapping", deleteBody(port));
	}

	private static String deleteBody(int port) {
		return "<NewRemoteHost></NewRemoteHost>"
			+ "<NewExternalPort>" + port + "</NewExternalPort>"
			+ "<NewProtocol>TCP</NewProtocol>";
	}

	private String call(String action, String body) {
		String envelope = "<?xml version=\"1.0\"?>"
			+ "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
			+ " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
			+ "<u:" + action + " xmlns:u=\"" + this.serviceType + "\">" + body + "</u:" + action + ">"
			+ "</s:Body></s:Envelope>";
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(this.controlUrl))
				.header("Content-Type", "text/xml; charset=\"utf-8\"")
				.header("SOAPAction", "\"" + this.serviceType + "#" + action + "\"")
				.timeout(Duration.ofSeconds(5))
				.POST(HttpRequest.BodyPublishers.ofString(envelope))
				.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200) {
				return response.body();
			}
		} catch (Throwable ignored) {
			// call failed
		}
		return null;
	}

	/** True when the address is a routable public IPv4 (not LAN/CGNAT/special-purpose). */
	public static boolean isPublicIpv4(String ip) {
		if (ip == null) {
			return false;
		}
		Matcher matcher = IPV4_PATTERN.matcher(ip.trim());
		if (!matcher.matches()) {
			return false;
		}
		int a = Integer.parseInt(matcher.group(1));
		int b = Integer.parseInt(matcher.group(2));
		int c = Integer.parseInt(matcher.group(3));
		int d = Integer.parseInt(matcher.group(4));
		if (a > 255 || b > 255 || c > 255 || d > 255) {
			return false;
		}
		if (a == 0 || a == 10 || a == 127 || a >= 224) {
			return false;
		}
		if (a == 100 && b >= 64 && b <= 127) {
			return false; // CGNAT range
		}
		if (a == 169 && b == 254) {
			return false;
		}
		if (a == 172 && b >= 16 && b <= 31) {
			return false;
		}
		if (a == 192 && b == 168) {
			return false;
		}
		if (a == 192 && b == 0) {
			return false;
		}
		if (a == 198 && (b == 18 || b == 19)) {
			return false;
		}
		if (a == 198 && b == 51 && c == 100) {
			return false;
		}
		if (a == 203 && b == 0 && c == 113) {
			return false;
		}
		return true;
	}

	private static String xmlEscape(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
