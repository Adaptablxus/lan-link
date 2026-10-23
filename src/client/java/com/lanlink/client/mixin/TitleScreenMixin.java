package com.lanlink.client.mixin;

import com.lanlink.client.LanLink;
import com.lanlink.client.LanLinkBackgroundScreen;
import net.minecraft.ChatFormatting;
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
 *
 * The button sits in the top-left corner: the only spot the vanilla title screen
 * keeps free at every window size (logo is centred at y=30, splash is mid-right,
 * the version line is bottom-left and the copyright is bottom-right). Its width
 * and label adapt to the window so nothing ever runs off-screen.
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
		Minecraft minecraft = Minecraft.getInstance();
		Screen self = (Screen) (Object) this;
		String world = server.getWorldData().getLevelName();
		if (world.length() > 24) {
			world = world.substring(0, 23) + "...";
		}

		int buttonWidth = Math.min(200, Math.max(110, self.width / 3));
		Component dot = Component.literal("● ").withStyle(ChatFormatting.GREEN);
		int budget = buttonWidth - 12 - minecraft.font.width(dot);
		Component text = Component.translatableWithFallback("lanlink.keepalive.button", "LAN Link: rejoin \"%s\"", world);
		if (minecraft.font.width(text) > budget) {
			text = Component.translatableWithFallback("lanlink.keepalive.button2", "Rejoin \"%s\"", world);
		}
		if (minecraft.font.width(text) > budget) {
			text = Component.translatableWithFallback("lanlink.keepalive.button3", "LAN Link: rejoin");
		}
		if (minecraft.font.width(text) > budget) {
			text = Component.translatableWithFallback("lanlink.keepalive.button4", "LAN Link");
		}
		Component label = dot.copy().append(text);

		Component tooltip = Component.translatableWithFallback(
			"lanlink.keepalive.button.tooltip",
			"LAN Link: \"%s\" is still running for friends.\nClick to rejoin, copy the address or stop it.",
			world
		);
		Button button = Button.builder(label, b -> minecraft.gui.setScreen(new LanLinkBackgroundScreen(self)))
			.bounds(4, 4, buttonWidth, 20)
			.tooltip(Tooltip.create(tooltip))
			.build();
		((ScreenInvoker) (Object) this).lanlink$invokeAddRenderableWidget(button);
	}
}
