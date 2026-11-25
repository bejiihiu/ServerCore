package kz.bejiihiu.servercore.diagnostics

import kz.bejiihiu.servercore.boot.PluginContext
import kz.bejiihiu.servercore.integration.flectonepulse.FlectonePulseBridge
import org.bukkit.plugin.java.JavaPlugin

/**
 * the only server-facing part of the diagnostics feature: reads the live
 * installer objects once and freezes them into [DiagnosticsFacts]. every
 * value here is a cheap in-memory read, so the command can call it on the
 * global region thread without stalling a tick.
 */
class DiagnosticsProbe(
    private val ctx: PluginContext,
    private val sources: DiagnosticsSources,
) {

    fun facts(): DiagnosticsFacts {
        val plugin = ctx.plugin
        val client = sources.gateway.client
        val flectonePulseState = sources.flectonePulse.bridge.currentState()

        return DiagnosticsFacts(
            build = BuildFacts(
                pluginVersion = plugin.pluginMeta.version,
                jarName = plugin.jarFile.name,
                serverVersion = plugin.server.minecraftVersion,
                folia = FOLIA,
                javaVersion = System.getProperty("java.version") ?: "unknown",
            ),
            modules = ModuleFacts(
                guestEnabled = ctx.guestConfig.enabled,
                guestActive = sources.guest.active,
                packetConfigured = sources.packet.enabledByConfig,
                packetEventsPresent = versionOf("PacketEvents") != null,
                packetOnline = sources.packet.online,
                espActive = sources.esp.active,
                protectionEnabled = sources.protection.enabledByConfig,
                protectionActive = sources.protection.active,
                specEnabled = sources.spec.enabledByConfig,
                specActive = sources.spec.active,
                barsEnabled = sources.bars.enabledByConfig,
                barsActive = sources.bars.active,
                barsEnabledCount = sources.bars.enabledBarCount,
                luckPermsPresent = versionOf("LuckPerms") != null,
                placeholderApiPresent = versionOf("PlaceholderAPI") != null,
                flectonePulsePresent = flectonePulseState != FlectonePulseBridge.State.NOT_PRESENT,
                flectonePulseCompatible = flectonePulseState != FlectonePulseBridge.State.INCOMPATIBLE,
                flectonePulseConnected = flectonePulseState == FlectonePulseBridge.State.CONNECTED,
            ),
            link = LinkFacts(
                connected = client.isConnected(),
                lastHeartbeatAgeMs = client.lastHeartbeatAgeMs(),
                heartbeatIntervalMs = ctx.pluginConfig.gateway.heartbeatIntervalMs,
                outboxSize = client.outboxSize(),
                lastDisconnectReason = client.lastDisconnectReason(),
                reconnectAttempts = client.reconnectAttempts(),
            ),
            counts = CountFacts(
                guests = if (sources.guest.active) sources.guest.sessions.activeCount() else 0,
                specSessions = ctx.activeSpecSessions(),
                bars = sources.bars.enabledBarCount,
                onlinePlayers = plugin.server.onlinePlayers.size,
            ),
            versions = VersionFacts(
                packetEvents = versionOf("PacketEvents"),
                luckPerms = versionOf("LuckPerms"),
                placeholderApi = versionOf("PlaceholderAPI"),
                flectonePulse = versionOf("FlectonePulse"),
            ),
        )
    }

    private fun versionOf(pluginName: String): String? =
        (ctx.plugin.server.pluginManager.getPlugin(pluginName) as? JavaPlugin)?.pluginMeta?.version

    private companion object {
        /** folia's regionizer is the one class paper never ships */
        val FOLIA: Boolean = runCatching {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        }.isSuccess
    }
}
