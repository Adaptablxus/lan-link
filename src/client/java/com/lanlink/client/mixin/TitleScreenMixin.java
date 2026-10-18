package com.lanlink.client.mixin;

import com.lanlink.client.LanLink;
import com.lanlink.client.LanLinkBackgroundScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "rejoin the world" button to the title screen while the world is still
 * running for friends in the background (see {@link LanLink} background hosting).
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {
	@Inject(method = "init()V", at = @At("TAIL"))
	private void lanlink$addBackgroundHostingButton(CallbackInfo ci) {
		if (!LanLink.hasBackgroundHost()) {
			return;
		}
		IntegratedServer server = LanLink.getBackgroundServer();
		if (server == null) {
			return;
		}
		String world = server.getWorldData().getLevelName();
		if (world.length() > 24) {
			world = world.substring(0, 23) + "...";
		}
		int players = server.getPlayerList().getPlayers().size();
		Screen self = (Screen) (Object) this;
		Component label = Component.translatableWithFallback(
			"lanlink.keepalive.button",
			"LAN Link: rejoin \"%s\"%s",
			world,
			players > 0 ? " (" + players + " online)" : ""
		);
		Button button = Button.builder(label, b -> Minecraft.getInstance().gui.setScreen(new LanLinkBackgroundScreen(self)))
			.bounds(self.width / 2 - 100, self.height / 4 + 168, 200, 20)
			.tooltip(Tooltip.create(Component.translatableWithFallback(
				"lanlink.keepalive.button.tooltip",
				"Your world is still open for friends - friends stay connected while you are at the title screen."
			)))
			.build();
		((ScreenInvoker) (Object) this).lanlink$invokeAddRenderableWidget(button);
	}
}
