package com.lanlink.client.mixin;

import com.lanlink.client.LanLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the world running for friends when the host quits to the title screen.
 *
 * Vanilla stops the integrated server inside {@code disconnect(...)} (via {@code server.halt}).
 * When the owner is quitting to the title screen while hosting, the halt is skipped, the server
 * stays alive in the background (friends keep playing, the LAN session and its tunnel stay open)
 * and it is only stopped when the owner explicitly closes it (title screen button) or closes
 * the game - the game exit path stops and saves it in {@code close()}.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
	@Inject(method = "disconnectWithSavingScreen()V", at = @At("HEAD"))
	private void lanlink$requestKeepAlive(CallbackInfo ci) {
		if (LanLink.shouldKeepAliveOnQuit()) {
			LanLink.requestKeepAlive(Minecraft.getInstance().getSingleplayerServer());
		}
	}

	@Inject(method = "disconnectWithSavingScreen()V", at = @At("RETURN"))
	private void lanlink$clearKeepAliveRequest(CallbackInfo ci) {
		LanLink.clearKeepAliveRequest();
	}

	@Redirect(
		method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/IntegratedServer;halt(Z)V")
	)
	private void lanlink$keepHostingInsteadOfHalting(IntegratedServer server, boolean wait) {
		if (!LanLink.tryAdoptForKeepAlive(server)) {
			server.halt(wait);
		}
	}

	@Redirect(
		method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/server/IntegratedServer;isShutdown()Z")
	)
	private boolean lanlink$keptAliveIsNotWaitedFor(IntegratedServer server) {
		return LanLink.isKeptAlive(server) || server.isShutdown();
	}

	@Inject(method = "close()V", at = @At("HEAD"))
	private void lanlink$stopBackgroundHostOnExit(CallbackInfo ci) {
		LanLink.shutDownBackgroundHosting();
	}
}
