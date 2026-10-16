package com.lanlink.client.mixin;

import com.lanlink.client.LanLinkScreen;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PauseScreen.class)
public class PauseScreenMixin {
	/**
	 * The pause menu creates the "Open to LAN" screen inside
	 * {@code lambda$createPauseMenu$9}. Replace it with the LAN Link screen.
	 */
	@Redirect(
		method = "lambda$createPauseMenu$9",
		at = @At(
			value = "NEW",
			target = "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/gui/screens/MultiplayerOptionsScreen;"
		)
	)
	private MultiplayerOptionsScreen lanlink$openLanLinkScreen(Screen lastScreen) {
		return new LanLinkScreen(lastScreen);
	}
}
