package com.lanlink.client.mixin;

import com.lanlink.client.LanLink;
import com.lanlink.client.LanLinkBackgroundScreen;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a world is still running for friends in the background, opening or creating
 * another world is intercepted: a second integrated server cannot run at the same time,
 * so the player is asked to close the background world first (or rejoin it).
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {
	@Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
	private void lanlink$guardWorldOpen(String levelId, Runnable onCancel, CallbackInfo ci) {
		if (!LanLink.hasBackgroundHost()) {
			return;
		}
		ci.cancel();
		Minecraft minecraft = Minecraft.getInstance();
		Screen parent = minecraft.gui.screen();
		minecraft.gui.setScreen(new LanLinkBackgroundScreen(parent,
			() -> ((WorldOpenFlows) (Object) this).openWorld(levelId, onCancel)));
	}

	@Inject(method = "createFreshLevel", at = @At("HEAD"), cancellable = true)
	private void lanlink$guardWorldCreate(
		String levelId,
		LevelSettings levelSettings,
		WorldOptions options,
		Function<HolderLookup.Provider, WorldDimensions> dimensionsProvider,
		Screen parentScreen,
		CallbackInfo ci
	) {
		if (!LanLink.hasBackgroundHost()) {
			return;
		}
		ci.cancel();
		Minecraft minecraft = Minecraft.getInstance();
		Screen parent = minecraft.gui.screen();
		minecraft.gui.setScreen(new LanLinkBackgroundScreen(parent,
			() -> ((WorldOpenFlows) (Object) this).createFreshLevel(levelId, levelSettings, options, dimensionsProvider, parentScreen)));
	}
}
