package com.lanlink.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public class LanLinkConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static LanLinkConfig instance;

	/** Public relay used to forward internet connections to this computer. */
	public String relayHost = "bore.pub";
	public int relayPort = 7835;

	/** Use the QUIClime relay network (e4mc's open relays, chosen by location) as the primary path. */
	public boolean useQuiclime = true;

	/** The relay's last known numeric address, so reconnects skip DNS. */
	public String cachedIp;
	public String cachedForHost;

	/** Let friends on older Minecraft versions (1.8.9 and up) join the shared world. */
	public boolean allowOlderClients = true;

	/** Let friends on newer Minecraft versions join the shared world. */
	public boolean allowNewerClients = true;

	/** Keep the world running for friends when you leave it (quit to the title screen).
	 * It then closes only when you stop it from the title screen or close the game. */
	public boolean keepWorldRunningWhenLeaving = true;

	public static synchronized LanLinkConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public String getCachedIp(String host) {
		return host != null && host.equalsIgnoreCase(this.cachedForHost) ? this.cachedIp : null;
	}

	public void putCachedIp(String host, String ip) {
		this.cachedForHost = host;
		this.cachedIp = ip;
		save();
	}

	public void clearCachedIp(String host) {
		if (host != null && host.equalsIgnoreCase(this.cachedForHost)) {
			this.cachedForHost = null;
			this.cachedIp = null;
			save();
		}
	}

	public void setClientVersionPolicy(boolean allowOlder, boolean allowNewer) {
		this.allowOlderClients = allowOlder;
		this.allowNewerClients = allowNewer;
		save();
	}

	private static LanLinkConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("lanlink.json");
		LanLinkConfig config = new LanLinkConfig();
		try {
			if (Files.exists(path)) {
				LanLinkConfig read = GSON.fromJson(Files.readString(path), LanLinkConfig.class);
				if (read != null) {
					config = read;
				}
			}
			if (config.relayHost == null || config.relayHost.isBlank()) {
				config.relayHost = "bore.pub";
			}
			if (config.relayPort <= 0 || config.relayPort > 65535) {
				config.relayPort = 7835;
			}
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(config));
		} catch (Exception e) {
			LanLinkClient.LOGGER.warn("Could not read/write the config, using defaults", e);
		}
		return config;
	}

	private void save() {
		try {
			Path path = FabricLoader.getInstance().getConfigDir().resolve("lanlink.json");
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (Exception e) {
			LanLinkClient.LOGGER.warn("Could not save the config", e);
		}
	}
}
