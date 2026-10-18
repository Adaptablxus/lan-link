package com.lanlink.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Shown while the world is still running for friends in the background:
 * rejoin it, stop it, or - when used as the "another world is being opened"
 * guard - stop it and continue with the requested action.
 */
public class LanLinkBackgroundScreen extends Screen {
	private static final Component TITLE = Component.translatableWithFallback("lanlink.keepalive.title", "World open in background");
	private static final Component REJOIN = Component.translatableWithFallback("lanlink.keepalive.rejoin", "Rejoin world");
	private static final Component STOP = Component.translatableWithFallback("lanlink.keepalive.stop", "Stop and close it");
	private static final Component STOP_CONTINUE = Component.translatableWithFallback("lanlink.keepalive.stop_continue", "Stop it and continue");

	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final Screen parent;
	private final Runnable continueAfterStop;

	private StringWidget playersWidget;
	private Button stopButton;
	private boolean stopping;

	public LanLinkBackgroundScreen(Screen parent) {
		this(parent, null);
	}

	public LanLinkBackgroundScreen(Screen parent, Runnable continueAfterStop) {
		super(TITLE);
		this.parent = parent;
		this.continueAfterStop = continueAfterStop;
	}

	@Override
	protected void init() {
		IntegratedServer server = LanLink.getBackgroundServer();
		if (server == null || server.isShutdown()) {
			this.onClose();
			return;
		}

		this.layout.addTitleHeader(TITLE, this.font);
		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(6));
		content.defaultCellSetting().alignHorizontallyCenter();

		content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.keepalive.world",
			"World: %s", server.getWorldData().getLevelName()), this.font));
		this.playersWidget = content.addChild(new StringWidget(playersLine(server), this.font));
		String address = LanLink.getPublicAddress();
		if (address != null) {
			content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.keepalive.address",
				"Address: %s", address).withStyle(ChatFormatting.GREEN), this.font));
			content.addChild(Button.builder(Component.translatableWithFallback("lanlink.keepalive.copy", "Copy address"),
				b -> this.minecraft.keyboardHandler.setClipboard(address)).width(150).build());
		}
		content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.keepalive.info",
			"You left the world, but it keeps running for friends. It closes when you stop it here or close Minecraft."), this.font));

		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(Button.builder(REJOIN, b -> {
			b.active = false;
			LanLink.rejoinBackgroundWorld(this);
		}).width(140).build());
		this.stopButton = footer.addChild(Button.builder(
			this.continueAfterStop != null ? STOP_CONTINUE : STOP, b -> this.stopHosting()).width(160).build());
		footer.addChild(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose()).width(60).build());

		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	@Override
	public void tick() {
		super.tick();
		IntegratedServer server = LanLink.getBackgroundServer();
		boolean alive = server != null && !server.isShutdown();
		if (this.playersWidget != null && alive) {
			this.playersWidget.setMessage(playersLine(server));
		}
		if (this.stopButton != null) {
			this.stopButton.active = alive && !this.stopping;
			this.stopButton.setMessage(this.stopping
				? Component.translatableWithFallback("lanlink.keepalive.stopping", "Closing...")
				: (this.continueAfterStop != null ? STOP_CONTINUE : STOP));
		}
		if (!alive && !this.stopping && this.minecraft.gui.screen() == this) {
			this.onClose();
		}
	}

	@Override
	public void onClose() {
		if (this.parent != null) {
			this.minecraft.gui.setScreen(this.parent);
		} else {
			super.onClose();
		}
	}

	private void stopHosting() {
		if (this.stopping) {
			return;
		}
		this.stopping = true;
		LanLink.stopBackgroundHosting(() -> {
			this.stopping = false;
			if (this.minecraft.gui.screen() != this) {
				return;
			}
			if (this.continueAfterStop != null) {
				this.continueAfterStop.run();
			} else if (this.parent != null) {
				this.minecraft.gui.setScreen(this.parent);
			}
		});
	}

	private static Component playersLine(IntegratedServer server) {
		int players = server.getPlayerList().getPlayers().size();
		if (players > 0) {
			return Component.translatableWithFallback("lanlink.keepalive.players",
				"Friends online: %s - they stay connected.", players);
		}
		return Component.translatableWithFallback("lanlink.keepalive.players.none",
			"No players online right now - friends can still join.");
	}
}
