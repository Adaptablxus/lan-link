package com.lanlink.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

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
	private static final Component COPIED = Component.translatableWithFallback("lanlink.copied", "Copied!");

	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final Screen parent;
	private final Runnable continueAfterStop;

	private StringWidget playersWidget;
	private Button stopButton;
	private Button copyButton;
	private Component copyOriginal;
	private long copiedUntil;
	private int textMax = 200;
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
		this.textMax = Math.max(120, this.width - 52);
		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(6));
		content.defaultCellSetting().alignHorizontallyCenter();

		content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.keepalive.running", "Running for friends")
			.withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), this.font));
		content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.keepalive.world", "World: %s",
			Component.literal(server.getWorldData().getLevelName()).withStyle(ChatFormatting.WHITE)), this.font)
			.setMaxWidth(this.textMax));
		this.playersWidget = content.addChild(new StringWidget(playersLine(server), this.font).setMaxWidth(this.textMax));

		String address = LanLink.getPublicAddress();
		String lan = LanLink.getLanAddress();
		String main = address != null
			? address
			: (lan != null && server.getPort() > 0 ? lan + ":" + server.getPort() : null);
		if (main != null) {
			content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.keepalive.address", "Address: %s",
				Component.literal(main).withStyle(ChatFormatting.GREEN)), this.font).setMaxWidth(this.textMax));
			this.copyButton = content.addChild(Button.builder(
				Component.translatableWithFallback("lanlink.keepalive.copy", "Copy address"),
				button -> this.copyAddress(button, main))
				.width(Math.min(150, this.textMax))
				.tooltip(Tooltip.create(Component.literal(main)))
				.build());
			if (address != null && lan != null && server.getPort() > 0) {
				content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.keepalive.wifi", "Same Wi-Fi: %s",
					lan + ":" + server.getPort()).withStyle(ChatFormatting.GRAY), this.font).setMaxWidth(this.textMax));
			}
		} else {
			content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.keepalive.address.none",
				"No address yet - the world is still starting up.").withStyle(ChatFormatting.GRAY), this.font)
				.setMaxWidth(this.textMax));
		}

		content.addChild(new MultiLineTextWidget(Component.translatableWithFallback("lanlink.keepalive.hint",
			"Friends join with vanilla Minecraft: Multiplayer -> Direct Connection.").withStyle(ChatFormatting.GRAY), this.font)
			.setMaxWidth(this.textMax).setCentered(true));
		content.addChild(new MultiLineTextWidget(Component.translatableWithFallback("lanlink.keepalive.info",
			"You left the world, but it keeps running for friends. It closes when you stop it here or close Minecraft."),
			this.font).setMaxWidth(this.textMax).setCentered(true));

		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		int buttonWidth = Math.max(70, Math.min(150, (this.width - 56) / 3));
		footer.addChild(Button.builder(REJOIN, b -> {
			b.active = false;
			LanLink.rejoinBackgroundWorld(this);
		}).width(buttonWidth).build());
		this.stopButton = footer.addChild(Button.builder(
			this.continueAfterStop != null ? STOP_CONTINUE : STOP, b -> this.stopHosting()).width(buttonWidth).build());
		footer.addChild(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose()).width(buttonWidth).build());

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
		if (this.copyButton != null && this.copyOriginal != null && Util.getMillis() >= this.copiedUntil) {
			this.copyButton.setMessage(this.copyOriginal);
			this.copyOriginal = null;
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

	/** Copies the address and briefly turns the button into a "Copied!" confirmation. */
	private void copyAddress(Button button, String value) {
		this.minecraft.keyboardHandler.setClipboard(value);
		if (this.copyButton != null && this.copyButton != button && this.copyOriginal != null) {
			this.copyButton.setMessage(this.copyOriginal);
		}
		this.copyButton = button;
		this.copyOriginal = button.getMessage();
		this.copiedUntil = Util.getMillis() + 1500L;
		button.setMessage(COPIED);
	}

	private Component playersLine(IntegratedServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		int count = players.size();
		if (count == 0) {
			return Component.translatableWithFallback("lanlink.keepalive.online.none",
				"No friends online right now - they can still join.").withStyle(ChatFormatting.GRAY);
		}
		List<String> names = new ArrayList<>();
		for (ServerPlayer player : players) {
			names.add(player.getName().getString());
		}
		int shown = names.size();
		Component line = onlineLine(count, names, 0);
		while (shown > 1 && this.font.width(line) > this.textMax) {
			shown--;
			line = onlineLine(count, names.subList(0, shown), count - shown);
		}
		return line.copy().withStyle(ChatFormatting.GREEN);
	}

	private static Component onlineLine(int count, List<String> names, int hidden) {
		String joined = String.join(", ", names);
		if (hidden > 0) {
			joined = joined + " +" + hidden;
		}
		return Component.translatableWithFallback("lanlink.keepalive.online", "Online (%s): %s", count, joined);
	}
}
