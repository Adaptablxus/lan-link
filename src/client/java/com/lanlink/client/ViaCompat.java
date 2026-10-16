package com.lanlink.client;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.protocol.version.BlockedProtocolVersions;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;

/**
 * Translates the LAN screen's "allow older/newer clients" toggles into
 * ViaVersion's blocked-version ranges, so friends outside the allowed range
 * are turned away with a vanilla-style disconnect message.
 * <p>
 * The runtime ViaVersion classes come bundled inside the nested ViaFabric jar;
 * the build only compiles against them.
 */
public final class ViaCompat {
	private ViaCompat() {
	}

	/** Applies the given policy to ViaVersion; call whenever the toggles are applied. */
	public static void applyPolicy(boolean allowOlder, boolean allowNewer) {
		try {
			ViaVersionConfig config = Via.getConfig();
			List<String> blockedVersions = new ArrayList<>(2);
			String disconnectMessage = null;

			if (!allowOlder || !allowNewer) {
				int serverProtocol = SharedConstants.getProtocolVersion();
				ProtocolVersion serverVersion = ProtocolVersion.getProtocol(serverProtocol);
				if (!serverVersion.isKnown()) {
					LanLinkClient.LOGGER.warn("Could not apply the client version policy: unrecognized server protocol {}", serverProtocol);
					return;
				}

				String versionName = serverVersion.getName();
				if (!allowOlder && !allowNewer) {
					blockedVersions.add("<" + versionName);
					blockedVersions.add(">" + versionName);
					disconnectMessage = "This server only accepts Minecraft " + versionName;
				} else if (!allowOlder) {
					blockedVersions.add("<" + versionName);
					disconnectMessage = "Outdated client! Please use " + versionName;
				} else {
					blockedVersions.add(">" + versionName);
					disconnectMessage = "Outdated server! I'm still on " + versionName;
				}
			}

			config.set("block-versions", blockedVersions);
			if (disconnectMessage != null) {
				config.set("block-disconnect-msg", disconnectMessage);
			}
			config.save();
			Via.getManager().getConfigurationProvider().reloadConfigs();

			BlockedProtocolVersions effective = config.blockedProtocolVersions();
			LanLinkClient.LOGGER.info("Client version policy applied (older clients: {}, newer clients: {}) - blocking below {}, above {}",
				allowOlder ? "allowed" : "blocked",
				allowNewer ? "allowed" : "blocked",
				effective.blocksBelow(),
				effective.blocksAbove());
		} catch (Throwable t) {
			LanLinkClient.LOGGER.warn("Could not apply the client version policy", t);
		}
	}
}
