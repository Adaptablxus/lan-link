package com.lanlink.client.mixin;

import com.lanlink.client.LanLink;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla stops the integrated server when the singleplayer owner logs out
 * ("Stopping singleplayer server as player logged out"). While the world is being
 * kept alive for friends in the background, that automatic stop is suppressed -
 * the world closes only when the owner stops it explicitly or closes the game.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerImplMixin {
	@Shadow
	@Final
	protected MinecraftServer server;

	@Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
	private void lanlink$keepBackgroundHostAlive(DisconnectionDetails details, CallbackInfo ci) {
		if (this.server instanceof IntegratedServer integratedServer && LanLink.shouldProtectFromOwnerStop(integratedServer)) {
			ci.cancel();
		}
	}
}
