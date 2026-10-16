quiclime-codec-0.0.74.Final.e4mc.1.jar
======================================

Unmodified classes from the e4mc project's fork of the netty incubator QUIC codec
(io.netty.incubator.codec.quic.* plus the shaded dev.dirs "directories" helper),
version 0.0.74.Final.e4mc.1.

Taken verbatim from the published e4mc mod jar (e4mc-fabric-6.2.3), whose classes are
proven to work with Minecraft's netty 4.2.x.

- Upstream codec: netty-incubator-codec-quic - Apache License 2.0, The Netty Project.
- Fork and relay protocol ("QUIClime"): e4mc by Skye (https://e4mc.link,
  https://github.com/vgskye/e4mc-quiclime), MIT-licensed.
- At runtime the codec downloads its native library from https://natives.e4mc.link/.

LAN Link uses these classes to speak the QUIClime protocol to e4mc's public relays,
so friends can join with plain vanilla Minecraft via Direct Connect. These classes are
shaded into the LAN Link mod jar by build.gradle.
