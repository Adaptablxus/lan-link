package com.lanlink.client.mixin;

import com.lanlink.client.LanLink;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public class IntegratedServerMixin {
	@Inject(method = "getMaxPlayers", at = @At("HEAD"), cancellable = true)
	private void lanlink$overrideMaxPlayers(CallbackInfoReturnable<Integer> cir) {
		int limit = LanLink.getMaxPlayers();
		if (limit > 0 && limit != 8) {
			cir.setReturnValue(limit);
		}
	}

	@Inject(method = "teardownPublishedState", at = @At("HEAD"))
	private void lanlink$stopTunnelOnTeardown(CallbackInfo ci) {
		LanLink.ensureNoTunnel();
	}
}
