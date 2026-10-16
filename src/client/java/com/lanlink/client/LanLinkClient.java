package com.lanlink.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LanLinkClient implements ClientModInitializer {
	public static final String MOD_ID = "lanlink";
	public static final Logger LOGGER = LoggerFactory.getLogger("LAN Link");

	@Override
	public void onInitializeClient() {
		LanLinkConfig.get();
		LOGGER.info("LAN Link ready - open a world and share it from the Open to LAN window");
	}
}
