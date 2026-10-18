package com.lanlink.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

/**
 * Replacement for the vanilla "Open to LAN" window. Adds an internet sharing
 * option (public address), an online mode toggle and a max players setting on
 * top of the vanilla LAN controls.
 */
public class LanLinkScreen extends MultiplayerOptionsScreen {
	public static final int PORT_LOWER_BOUND = 1024;
	public static final int PORT_HIGHER_BOUND = 65535;
	public static final int MAX_PLAYERS_LOWER_BOUND = 1;
	public static final int MAX_PLAYERS_HIGHER_BOUND = 100;

	private static final Component TITLE = Component.translatableWithFallback("lanlink.title", "Open to LAN");
	private static final Component PLAYER_SETTINGS_HEADER = Component.translatableWithFallback("lanlink.player_settings", "Player Settings");
	private static final Component ONLINE_MODE = Component.translatableWithFallback("lanlink.online_mode", "Online Mode");
	private static final Component MAX_PLAYERS = Component.translatableWithFallback("lanlink.max_players", "Max Players");
	private static final Component PUBLIC_ADDRESS = Component.translatableWithFallback("lanlink.public", "Public Address");
	private static final Component CLIENT_VERSIONS_HEADER = Component.translatableWithFallback("lanlink.client_versions", "Client Versions");
	private static final Component ALLOW_OLDER_CLIENTS = Component.translatableWithFallback("lanlink.allow_older", "Allow Older Clients");
	private static final Component ALLOW_NEWER_CLIENTS = Component.translatableWithFallback("lanlink.allow_newer", "Allow Newer Clients");
	private static final Component INVALID_PORT = Component.translatable("lanServer.port.invalid", PORT_LOWER_BOUND, PORT_HIGHER_BOUND);
	private static final Component PORT_UNAVAILABLE = Component.translatable("lanServer.port.unavailable", PORT_LOWER_BOUND, PORT_HIGHER_BOUND);

	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	private MinecraftServer.MultiplayerScope wantedScope = MinecraftServer.MultiplayerScope.OFF;
	private GameType gameMode = GameType.SURVIVAL;
	private boolean commands;
	private boolean onlineMode = true;
	private boolean sharePublic = true;
	private int port = 25565;
	private boolean portValid = true;
	private int maxPlayers = 8;
	private boolean maxPlayersValid = true;
	private boolean allowOlderClients = true;
	private boolean allowNewerClients = true;

	private MinecraftServer.MultiplayerScope initialScope = MinecraftServer.MultiplayerScope.OFF;
	private GameType initialGameMode = GameType.SURVIVAL;
	private boolean initialCommands;
	private boolean initialOnlineMode = true;
	private boolean initialSharePublic = true;
	private int initialPort = -1;
	private int initialMaxPlayers = 8;
	private boolean initialAllowOlderClients = true;
	private boolean initialAllowNewerClients = true;

	private Button applyButton;
	private EditBox portEdit;
	private StringWidget portLabel;
	private EditBox maxPlayersEdit;

	public LanLinkScreen(Screen lastScreen) {
		super(lastScreen);
	}

	@Override
	protected void init() {
		IntegratedServer server = this.minecraft.getSingleplayerServer();
		if (server == null) {
			this.onClose();
			return;
		}

		this.layout.addTitleHeader(TITLE, this.font);

		// Keep all rows inside the screen at small window sizes / large GUI scales:
		// when the space between the title and the footer is tight, tighten the spacing
		// and drop the section headers instead of drawing past the footer.
		int available = this.layout.getContentHeight();
		if (available <= 0) {
			available = this.height - 96; // header (33) + footer (60) + a small margin
		}
		boolean showSectionHeaders = available >= 226;
		int spacing = available >= 186 ? (showSectionHeaders ? 6 : 4) : 2;
		LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(spacing));
		content.defaultCellSetting().alignHorizontallyCenter();

		this.initialScope = server.getMultiplayerScope();
		this.initialPort = server.getPort();
		this.wantedScope = this.initialScope == MinecraftServer.MultiplayerScope.OFF
			? MinecraftServer.MultiplayerScope.LAN
			: this.initialScope;
		this.port = this.initialScope == MinecraftServer.MultiplayerScope.LAN && this.initialPort > 0
			? this.initialPort
			: HttpUtil.getAvailablePort();

		// LAN on/off
		content.addChild(CycleButton.onOffBuilder(this.wantedScope == MinecraftServer.MultiplayerScope.LAN)
			.withTooltip(lan -> Tooltip.create((lan ? MinecraftServer.MultiplayerScope.LAN : MinecraftServer.MultiplayerScope.OFF).getTooltip()))
			.create(Component.translatable("menu.multiplayerOptions.lan"), (button, value) -> {
				this.wantedScope = value ? MinecraftServer.MultiplayerScope.LAN : MinecraftServer.MultiplayerScope.OFF;
				this.updatePortControlsState();
				this.updateApplyChangesActiveState();
			}));

		// Port (label vertically centred against the box)
		LinearLayout portRow = LinearLayout.horizontal().spacing(6);
		portRow.defaultCellSetting().alignVerticallyMiddle();
		this.portLabel = portRow.addChild(new StringWidget(Component.translatable("lanServer.port"), this.font));
		this.portEdit = portRow.addChild(new EditBox(this.font, 200, 20, Component.translatable("lanServer.port")));
		this.portEdit.setResponder(value -> {
			this.setPortError(this.tryParsePort(value));
			this.updateApplyChangesActiveState();
		});
		this.portEdit.setValue(String.valueOf(this.port));
		content.addChild(portRow);

		// Player settings
		if (showSectionHeaders) {
			content.addChild(new StringWidget(PLAYER_SETTINGS_HEADER, this.font));
		}

		LinearLayout playersRow = content.addChild(LinearLayout.horizontal().spacing(8));
		this.gameMode = server.getGameTypeForOtherPlayers();
		this.initialGameMode = this.gameMode;
		playersRow.addChild(CycleButton.builder(GameType::getShortDisplayName, this.gameMode)
			.withValues(GameType.SURVIVAL, GameType.SPECTATOR, GameType.CREATIVE, GameType.ADVENTURE)
			.create(Component.translatable("selectWorld.gameMode"), (button, value) -> {
				this.gameMode = value;
				this.updateApplyChangesActiveState();
			}));
		this.commands = server.commandsAllowedForOtherPlayers();
		this.initialCommands = this.commands;
		playersRow.addChild(CycleButton.onOffBuilder(this.commands)
			.create(Component.translatable("selectWorld.allowCommands"), (button, value) -> {
				this.commands = value;
				this.updateApplyChangesActiveState();
			}));

		LinearLayout accessRow = content.addChild(LinearLayout.horizontal().spacing(8));
		this.onlineMode = server.usesAuthentication();
		this.initialOnlineMode = this.onlineMode;
		accessRow.addChild(CycleButton.onOffBuilder(this.onlineMode)
			.withTooltip(value -> Tooltip.create(Component.translatableWithFallback("lanlink.online_mode.tooltip",
				"On: players must be signed in to Minecraft. Off: anyone can join with any username.")))
			.create(ONLINE_MODE, (button, value) -> {
				this.onlineMode = value;
				this.updateApplyChangesActiveState();
			}));

		this.maxPlayers = LanLink.getMaxPlayers();
		this.initialMaxPlayers = this.maxPlayers;
		LinearLayout maxPlayersRow = LinearLayout.horizontal().spacing(6);
		maxPlayersRow.defaultCellSetting().alignVerticallyMiddle();
		maxPlayersRow.addChild(new StringWidget(MAX_PLAYERS, this.font));
		this.maxPlayersEdit = maxPlayersRow.addChild(new EditBox(this.font, 40, 20, MAX_PLAYERS));
		this.maxPlayersEdit.setMaxLength(3);
		this.maxPlayersEdit.setValue(String.valueOf(this.maxPlayers));
		this.maxPlayersEdit.setResponder(value -> {
			this.maxPlayersValid = this.tryParseMaxPlayers(value);
			this.updateApplyChangesActiveState();
		});
		accessRow.addChild(maxPlayersRow);

		// Client version compatibility (older / newer friends)
		if (showSectionHeaders) {
			content.addChild(new StringWidget(CLIENT_VERSIONS_HEADER, this.font));
		}

		LinearLayout versionsRow = content.addChild(LinearLayout.horizontal().spacing(8));
		this.allowOlderClients = LanLinkConfig.get().allowOlderClients;
		this.initialAllowOlderClients = this.allowOlderClients;
		versionsRow.addChild(CycleButton.onOffBuilder(this.allowOlderClients)
			.withTooltip(value -> Tooltip.create(Component.translatableWithFallback("lanlink.allow_older.tooltip",
				value
					? "Friends on older Minecraft versions (1.9 and up) can join."
					: "Clients on older versions are turned away with \"Outdated client!\".")))
			.create(ALLOW_OLDER_CLIENTS, (button, value) -> {
				this.allowOlderClients = value;
				this.updateApplyChangesActiveState();
			}));

		this.allowNewerClients = LanLinkConfig.get().allowNewerClients;
		this.initialAllowNewerClients = this.allowNewerClients;
		versionsRow.addChild(CycleButton.onOffBuilder(this.allowNewerClients)
			.withTooltip(value -> Tooltip.create(Component.translatableWithFallback("lanlink.allow_newer.tooltip",
				value
					? "Friends on newer Minecraft versions can join."
					: "Clients on newer versions are turned away with \"Outdated server!\".")))
			.create(ALLOW_NEWER_CLIENTS, (button, value) -> {
				this.allowNewerClients = value;
				this.updateApplyChangesActiveState();
			}));

		// Public sharing
		this.sharePublic = LanLink.isPublicSharingPreferred();
		this.initialSharePublic = this.sharePublic;
		content.addChild(CycleButton.onOffBuilder(this.sharePublic)
			.withTooltip(value -> Tooltip.create(Component.translatableWithFallback("lanlink.public.tooltip",
				"Gives you a shareable internet address so friends anywhere can join. No port forwarding needed.")))
			.create(PUBLIC_ADDRESS, (button, value) -> {
				this.sharePublic = value;
				this.updateApplyChangesActiveState();
			}));

		// Live public address / tunnel status
		LanLink.Status status = LanLink.getStatus();
		int textMax = Math.max(140, this.width - 52);
		if (status == LanLink.Status.ACTIVE && LanLink.getPublicAddress() != null) {
			String primary = LanLink.getPublicIpAddress() != null ? LanLink.getPublicIpAddress() : LanLink.getPublicAddress();
			if (LanLink.getPublicIpAddress() == null) {
				content.addChild(Button.builder(Component.translatableWithFallback("lanlink.copy_address", "Copy address: %s", primary), button ->
					this.minecraft.keyboardHandler.setClipboard(primary)
				).width(Math.min(288, textMax)).build());
			} else {
				// Both forms are available: keep them on one row to save vertical space.
				String alternative = LanLink.getPublicAddress();
				LinearLayout copyRow = content.addChild(LinearLayout.horizontal().spacing(8));
				int copyWidth = Math.min(150, (textMax - 8) / 2);
				copyRow.addChild(Button.builder(Component.translatableWithFallback("lanlink.copy_ip", "Copy Relay IP"), button ->
					this.minecraft.keyboardHandler.setClipboard(primary)
				).width(copyWidth).tooltip(Tooltip.create(Component.literal(primary))).build());
				copyRow.addChild(Button.builder(Component.translatableWithFallback("lanlink.copy_domain", "Copy Relay Domain"), button ->
					this.minecraft.keyboardHandler.setClipboard(alternative)
				).width(copyWidth).tooltip(Tooltip.create(Component.literal(alternative))).build());
			}
		} else if (status == LanLink.Status.CONNECTING) {
			content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.status.connecting", "Getting a public address...").withStyle(ChatFormatting.YELLOW), this.font).setMaxWidth(textMax));
		} else if (status == LanLink.Status.FAILED) {
			content.addChild(new StringWidget(Component.translatableWithFallback("lanlink.status.failed", "Public address failed: %s", String.valueOf(LanLink.getLastError())).withStyle(ChatFormatting.RED), this.font).setMaxWidth(textMax));
		}

		LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		this.applyButton = footer.addChild(Button.builder(Component.translatableWithFallback("lanlink.start", "Start LAN"), button -> this.applyChanges(server)).build());
		footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).build());

		this.layout.visitWidgets(this::addRenderableWidget);
		this.updatePortControlsState();
		this.updateApplyChangesActiveState();
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
	}

	private void applyChanges(IntegratedServer server) {
		this.minecraft.gui.setScreen(null);

		if (this.gameMode != this.initialGameMode) {
			server.setGameTypeForOtherPlayers(this.gameMode);
		}
		if (this.commands != this.initialCommands) {
			server.setCommandsAllowedForOtherPlayers(this.commands);
		}
		if (this.onlineMode != this.initialOnlineMode) {
			server.setUsesAuthentication(this.onlineMode);
			this.sendPublishMessage(this.onlineMode
				? Component.translatableWithFallback("lanlink.online_mode.on", "Online mode enabled - players must be signed in to Minecraft.")
				: Component.translatableWithFallback("lanlink.online_mode.off", "Online mode disabled - anyone can join with any username."));
		}
		LanLink.setMaxPlayers(this.maxPlayers);
		LanLink.setPublicSharingPreferred(this.sharePublic);
		LanLinkConfig.get().setClientVersionPolicy(this.allowOlderClients, this.allowNewerClients);
		ViaCompat.applyPolicy(this.allowOlderClients, this.allowNewerClients);

		boolean portChanged = this.wantedScope == MinecraftServer.MultiplayerScope.LAN
			&& this.initialScope == MinecraftServer.MultiplayerScope.LAN
			&& this.port != this.initialPort;
		if (this.wantedScope != this.initialScope || portChanged) {
			if (server.unpublishServer()) {
				this.sendPublishMessage(Component.translatable("menu.multiplayerOptions.publish.stopped"));
			}
			if (this.wantedScope != MinecraftServer.MultiplayerScope.OFF) {
				this.publish(server);
			}
		}

		if (server.isPublished() && this.sharePublic) {
			LanLink.ensureTunnel(server.getPort());
		} else {
			LanLink.ensureNoTunnel();
		}
	}

	private void publish(IntegratedServer server) {
		if (server.publishServer(MinecraftServer.MultiplayerScope.LAN, this.port)) {
			this.sendPublishMessage(Component.translatable("menu.multiplayerOptions.publish.started.lan", ComponentUtils.copyOnClickText(String.valueOf(this.port))));
			if (!this.sharePublic) {
				this.sendPublishMessage(Component.translatableWithFallback("lanlink.lan_only", "Public sharing is off - only players on your Wi-Fi can join."));
			}
		} else {
			this.sendPublishMessage(Component.translatable("commands.publish.failed"));
		}
	}

	private void sendPublishMessage(Component message) {
		this.minecraft.gui.hud.getChat().addClientSystemMessage(message);
		this.minecraft.getNarrator().saySystemQueued(message);
		this.minecraft.updateTitle();
	}

	private Component tryParsePort(String value) {
		if (value.isBlank()) {
			this.port = HttpUtil.getAvailablePort();
			return null;
		}
		try {
			int parsed = Integer.parseInt(value);
			if (parsed >= PORT_LOWER_BOUND && parsed <= PORT_HIGHER_BOUND) {
				boolean currentlyPublished = this.initialScope == MinecraftServer.MultiplayerScope.LAN && parsed == this.initialPort;
				if (currentlyPublished || HttpUtil.isPortAvailable(parsed)) {
					this.port = parsed;
					return null;
				}
				return PORT_UNAVAILABLE;
			}
			this.port = HttpUtil.getAvailablePort();
			return INVALID_PORT;
		} catch (NumberFormatException e) {
			this.port = HttpUtil.getAvailablePort();
			return INVALID_PORT;
		}
	}

	private void setPortError(Component error) {
		if (this.portEdit == null) {
			return;
		}
		this.portValid = error == null;
		if (error == null) {
			this.portEdit.setTextColor(-2039584);
			this.portEdit.setTooltip(null);
		} else {
			this.portEdit.setTextColor(-2142128);
			this.portEdit.setTooltip(Tooltip.create(error));
		}
	}

	private boolean tryParseMaxPlayers(String value) {
		try {
			int parsed = Integer.parseInt(value.trim());
			if (parsed < MAX_PLAYERS_LOWER_BOUND || parsed > MAX_PLAYERS_HIGHER_BOUND) {
				return false;
			}
			this.maxPlayers = parsed;
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private void updatePortControlsState() {
		boolean lan = this.wantedScope == MinecraftServer.MultiplayerScope.LAN;
		if (this.portEdit != null) {
			this.portEdit.setEditable(lan);
			this.portEdit.active = lan;
			if (!lan) {
				this.portEdit.setFocused(false);
			}
		}
		if (this.portLabel != null) {
			this.portLabel.setMessage(lan
				? Component.translatable("lanServer.port")
				: Component.translatable("lanServer.port").withStyle(ChatFormatting.GRAY));
		}
	}

	private void updateApplyChangesActiveState() {
		if (this.applyButton == null) {
			return;
		}
		boolean portOk = this.wantedScope != MinecraftServer.MultiplayerScope.LAN || this.portValid;
		boolean changed = this.wantedScope != this.initialScope
			|| this.gameMode != this.initialGameMode
			|| this.commands != this.initialCommands
			|| this.onlineMode != this.initialOnlineMode
			|| this.sharePublic != this.initialSharePublic
			|| this.maxPlayers != this.initialMaxPlayers
			|| this.port != this.initialPort
			|| this.allowOlderClients != this.initialAllowOlderClients
			|| this.allowNewerClients != this.initialAllowNewerClients;
		this.applyButton.active = portOk && this.maxPlayersValid && changed;
		boolean firstStart = this.initialScope == MinecraftServer.MultiplayerScope.OFF
			&& this.wantedScope == MinecraftServer.MultiplayerScope.LAN;
		this.applyButton.setMessage(firstStart
			? Component.translatableWithFallback("lanlink.start", "Start LAN")
			: Component.translatable("menu.multiplayerOptions.applyChanges"));
	}
}
