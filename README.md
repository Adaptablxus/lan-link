# LAN Link

**Share your Open-to-LAN world over the internet — e4mc style, without port forwarding.**

LAN Link replaces Minecraft's "Open to LAN" window with an upgraded version: on top of the
normal LAN options it can create a public tunnel to your world and post a shareable address
in chat that your friends can use from anywhere.

Only **you** (the host) need this mod. Your friends just need vanilla Minecraft — any
launcher works (cracked/non-premium included) — they paste the address into Multiplayer
-> Direct Connection.

## Features

The new Open to LAN window adds:

- **Public Address (Internet)** — creates a public relay tunnel and posts a join address in
  chat, e.g. a private domain like `dilute-barista.ap.e4mc.link` (the closest relay is picked
  automatically — Asia-Pacific for India). Friends paste it into Multiplayer -> Direct
  Connection. No port forwarding, no router setup, no accounts anywhere.
- **Online Mode** — on (default): only players signed in to their Minecraft account can join.
  Off: anyone can join with any username — classic offline LAN, cracked/non-premium
  launchers included.
- **Max Players** — set the player limit (vanilla LAN is locked to 8).
- **Client Versions** — let friends on older/newer Minecraft versions join (the mod
  translates between versions automatically — see below).
- **Keeps hosting after you leave** — quitting to the title screen no longer closes the
  world: friends stay connected and new friends can still join. A LAN Link button in the
  title screen's top-left corner lets you rejoin, copy the address, or stop and close the
  world (see [Leaving the world](#leaving-the-world-keep-hosting-for-friends)).
- Plus the usual: LAN on/off, port, game mode for other players, allow cheats.

Safety nets built in:

- The primary path speaks the open **QUIClime** protocol (UDP/QUIC) to the e4mc relay network,
  which auto-selects the relay closest to you. For India that relay is ~70 ms away instead of
  bore.pub's ~260 ms in New Jersey.
- If the primary relay cannot be reached, LAN Link automatically falls back to the classic
  `bore.pub` relay (the numeric IP form is posted in chat, and if your ISP's DNS blocks or
  hijacks the relay's domain it is resolved over HTTPS via Google/Cloudflare DNS instead).
- Friends on the same Wi-Fi also get a direct `192.168.x.x:port` address in the message,
  which bypasses the relay entirely for the best possible latency.
- If your internet connection has **IPv6** (most Airtel/Jio/BSNL lines in India do), chat
  also shows a `[2401:...]:port` line. Friends whose internet is also IPv6 connect straight
  to you - no relay, no detour, the lowest possible latency.
- Leaving the world does **not** cut friends off — the LAN session and tunnel stop only when
  you stop hosting (title-screen button or Stop LAN) or close Minecraft.

## Friend version support (nothing to install for them)

Your friends join with plain vanilla Direct Connect — the whole ViaVersion translation
suite is bundled inside this mod:

- **Older versions** — friends on **1.9 and up** (1.16.x, 1.20.x, 1.21.x, ...) can join
  your 26.2 world. 1.7/1.8 support exists upstream but may not connect reliably yet
  (known upstream bug — 1.9+ recommended).
- **Newer versions** — friends on **26.3** can join.
- Blocks/items from your world may look substituted on much older clients: an old client
  cannot show things that did not exist in its version (e.g. a newer block may appear as
  a similar older one).

The **Client Versions** toggles in the Open to LAN window control this live (no restart):

- **Allow Older Clients** off — old-version players are turned away with the standard
  *"Outdated client! Please use 26.2"*.
- **Allow Newer Clients** off — new-version players are turned away with
  *"Outdated server! I'm still on 26.2"*.

Blocked players also see the world as incompatible in their multiplayer list. Both
toggles are remembered in `config/lanlink.json`.

## Requirements

- Minecraft Java **26.2** with **Fabric Loader 0.19.5+**
- Java 25
- No Fabric API needed

## Using it

1. Open your singleplayer world.
2. Press **Esc -> Open to LAN** (the multiplayer options button).
3. Leave **Public Address: On** (default), adjust Online Mode / Max Players / Client
   Versions if you like, and click **Start LAN**.
4. The chat posts the address — click it to copy, then send it to your friends.
5. When you want to take a break, just quit to the title screen: the world keeps running
   for friends. Rejoin from the LAN Link button (top-left) whenever you want.

## Leaving the world (keep hosting for friends)

Quitting to the title screen keeps the world, the LAN session and the relay tunnel running:

- Friends who are already in the world **stay connected**.
- Friends can **still join** while you are at the title screen — the address keeps working.
- A **LAN Link button in the top-left corner of the title screen** opens the
  **World open in background** screen, where you can:
  - **Rejoin world** — jump straight back in, with everyone where they were.
  - **Copy address** — share it with someone new.
  - **Stop and close it** — saves and closes the world properly for everyone.

The world closes only when you stop it from that screen, use **Stop LAN**, or close
Minecraft — all of which save the world fully. While it is running in the background,
trying to open a different world shows the same screen instead, so two worlds never fight
over the same port.

Prefer the old behaviour (leaving the world closes it)? Set
`"keepWorldRunningWhenLeaving": false` in `config/lanlink.json`.

## Connection quality

Remote connections go through a relay. LAN Link's default path uses the open e4mc relay
network (QUIClime protocol): a broker picks the relay closest to you — for India that is the
Asia-Pacific relay, roughly **70 ms** away instead of bore.pub's ~260 ms in New Jersey.

- Friends **on your Wi-Fi** should use the `192.168.x.x:port` address from the chat message:
  those connections go directly to your computer, no relay involved.
- For the absolute lowest latency, run your own relay on a small server near the players —
  see **[RELAY-SETUP.md](RELAY-SETUP.md)**. Set `"useQuiclime": false` in
  `config/lanlink.json` to make your own relay the primary path.
- Home connections behind **CGNAT** (common with Airtel/Jio) cannot accept incoming
  **IPv4** connections at all, even with router port forwarding - but IPv6 is usually *not*
  CGNAT'd, so try the `[2401:...]:port` line first. If the friend's internet has no IPv6
  either, a relay is the way around it.

## Configuration

`config/lanlink.json` (created on first launch):

```json
{
  "useQuiclime": true,
  "relayHost": "bore.pub",
  "relayPort": 7835,
  "allowOlderClients": true,
  "allowNewerClients": true,
  "keepWorldRunningWhenLeaving": true
}
```

`useQuiclime` (default `true`) uses the e4mc relay network as the primary path. Set it to
`false` to prefer the classic [bore](https://github.com/ekzhang/bore) relay configured by
`relayHost`/`relayPort` — you can point those at any bore-compatible relay, including your
own. The classic relay also runs automatically as a fallback whenever the primary path
cannot connect.

`allowOlderClients` / `allowNewerClients` mirror the Client Versions toggles and are saved
whenever you apply changes in the Open to LAN window.

`keepWorldRunningWhenLeaving` (default `true`) keeps the world and tunnel alive when you
quit to the title screen. Set it to `false` if you want the world to close as soon as you
leave it (the old behaviour).

## Building

```sh
./gradlew build
```

Output jar: `build/libs/lan-link-1.1.0.jar` — drop it into your `mods` folder.

To test in the dev environment: `./gradlew runClient`.

## How it works

- The mod redirects the pause menu's "Open to LAN" button to its own screen
  (mixin on `PauseScreen`).
- Starting LAN publishes the integrated server (`IntegratedServer.publishServer`) with your
  chosen port, then opens a relay tunnel:
  - **primary** — QUIC (UDP) to the closest e4mc relay. The relay assigns a private domain
    and forwards each player connection over a QUIC stream to `127.0.0.1:lanPort`
    (protocol: e4mc's open-source QUIClime / the e4mc network).
  - **fallback** — the classic bore TCP tunnel to `bore.pub` (or a relay of your choice).
- Online Mode maps to `MinecraftServer.setUsesAuthentication`, Max Players to a mixin on
  `IntegratedServer.getMaxPlayers()`.
- Friend version translation is the ViaVersion suite (ViaFabric + ViaBackwards +
  ViaRewind) nested inside the mod jar; the Client Versions toggles set ViaVersion's
  blocked-version ranges (`<26.2` / `>26.2`) at runtime when you apply changes.
- The public address is broadcast to all players and shown in chat with click-to-copy.
- Quitting to the title screen is intercepted (`Minecraft.disconnectWithSavingScreen` and
  `Minecraft.disconnect` mixins): instead of halting the integrated server, LAN Link keeps
  it ticking with the tunnel open, so friends stay connected and can still join. A
  title-screen button and the "World open in background" screen handle rejoining and
  stopping.
- Stopping from the title screen — or closing Minecraft — saves the world and shuts the
  integrated server and tunnel down cleanly.
- Tunnel sockets use TCP_NODELAY with 64 KiB copy buffers, so small game packets are
  forwarded immediately instead of being batched by the OS.

## Version history

- **1.1.0** — keep the world running for friends after you leave it; title-screen rejoin
  button and "World open in background" screen; UI fixes (equal-width buttons, all text
  clamped to the window).
- **1.0.1** — the Open to LAN window fits any window size / GUI scale; labels centred.
- **1.0.0** — first release: public shareable address (e4mc QUIClime relay + bore
  fallback), Online Mode toggle, Max Players, friend version support 1.9–26.3.

## Credits

- **QUIClime** relay protocol, relay network and the QUIC codec fork: the open-source
  [e4mc](https://e4mc.link) project (MIT; codec classes from its netty-incubator-codec-quic
  fork, Apache 2.0 — see `libs/README.txt`).
- Classic relay: [bore](https://github.com/ekzhang/bore) by Eric Zhang.
