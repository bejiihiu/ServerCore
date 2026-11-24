package kz.bejiihiu.servercore.guest

import java.util.UUID
import java.util.logging.Logger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration

/** toggles for every restricted interaction, true means the guest is blocked from it */
data class RestrictionToggles(
    val blockInteraction: Boolean,
    val blockBreak: Boolean,
    val blockPlace: Boolean,
    val itemPickup: Boolean,
    val itemUse: Boolean,
    val containerInteraction: Boolean,
    val entityDamage: Boolean,
    val commands: Boolean,
    val gamemodeChange: Boolean,
    val portals: Boolean,
    val inventoryEdit: Boolean,
    /** full chat isolation: the guest neither sees nor writes chat */
    val chat: Boolean,
) {
    companion object {
        fun allRestricted() = RestrictionToggles(
            blockInteraction = true, blockBreak = true, blockPlace = true,
            itemPickup = true, itemUse = true, containerInteraction = true,
            entityDamage = true, commands = true, gamemodeChange = true,
            portals = true, inventoryEdit = true, chat = true,
        )
    }
}

data class GuestConfig(
    val enabled: Boolean,
    val restoreOnRestart: Boolean,
    /** how long the ws-backed resolver waits for an auth verdict before failing closed to guest */
    val resolveTimeoutMs: Long,
    /** max guest sessions per player before the register-now wall, per the product decision */
    val sessionLimit: Int,
    /** what happens past the limit: true kicks with a register message, false warns but lets them look */
    val kickAfterLimit: Boolean,
    /** subtle actionbar reminder cadence while a guest waits for auth, seconds */
    val reminderIntervalSeconds: Long,
    /** how often the gamemode drift sweep re-checks a guest, seconds */
    val gamemodeSweepSeconds: Long,
    /** command names (no slash) a guest may still run */
    val commandWhitelist: Set<String>,
    /** telegram bot username, used to build the fallback deep link */
    val botUsername: String,
    /** personal deep links expire, they are one-time tokens after all */
    val linkTtlMs: Long,
    /** where the durable snapshot file lives, relative to the plugin data folder */
    val snapshotStoragePath: String,
    /** ui language, maps to messages_<lang>.yml in the plugin data folder */
    val lang: String,
    val restrictions: RestrictionToggles,
    /** uuid -> authorized|banned overrides that work even with the backend down.
     *  offline-mode uuids are just nickname derivatives, so this is convenience
     *  bookkeeping, never a security boundary */
    val localAccounts: Map<UUID, String>,
) {
    val fallbackBotUrl: String get() = "https://t.me/$botUsername"

    companion object {
        fun load(file: FileConfiguration, logger: Logger): GuestConfig {
            val guest = file.getConfigurationSection("guest")
            fun section(name: String): ConfigurationSection? = guest?.getConfigurationSection(name)
            fun bool(name: String, default: Boolean) = guest?.getBoolean(name, default) ?: default

            val whitelist = guest?.getStringList("command-whitelist")
                ?.map { it.trim().removePrefix("/").lowercase() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
                .ifEmpty { setOf("help", "rules", "link") }

            val localAccounts = buildMap {
                section("local-accounts")?.getKeys(false)?.forEach { key ->
                    val uuid = runCatching { UUID.fromString(key) }.getOrNull()
                    if (uuid == null) {
                        logger.warning("guest.local-accounts: '$key' is not a uuid, entry ignored")
                    } else {
                        put(uuid, section("local-accounts")?.getString(key, "").orEmpty().lowercase())
                    }
                }
            }

            return GuestConfig(
                enabled = bool("enabled", true),
                restoreOnRestart = bool("restore-on-restart", true),
                resolveTimeoutMs = guest?.getLong("resolve-timeout-milliseconds", 4000L)?.coerceIn(250L, 30_000L) ?: 4000L,
                sessionLimit = guest?.getInt("session-limit", 3)?.coerceIn(1, 100) ?: 3,
                kickAfterLimit = bool("kick-after-limit", true),
                reminderIntervalSeconds = guest?.getLong("reminder-interval-seconds", 60L)?.coerceIn(10L, 3600L) ?: 60L,
                gamemodeSweepSeconds = guest?.getLong("gamemode-sweep-seconds", 2L)?.coerceIn(1L, 60L) ?: 2L,
                commandWhitelist = whitelist,
                botUsername = guest?.getString("bot-username", "ServerCoreBot")?.trim()?.trimStart('@')
                    ?.ifEmpty { "ServerCoreBot" } ?: "ServerCoreBot",
                linkTtlMs = guest?.getLong("link-ttl-milliseconds", 90_000L)?.coerceIn(5_000L, 600_000L) ?: 90_000L,
                snapshotStoragePath = guest?.getString("snapshot-storage-path", "guest-snapshots.json")
                    ?.trim()?.ifEmpty { "guest-snapshots.json" } ?: "guest-snapshots.json",
                lang = guest?.getString("lang", "ru")?.trim()?.ifEmpty { "ru" } ?: "ru",
                restrictions = section("restrictions")?.let { restrictionsFrom(it) } ?: RestrictionToggles.allRestricted(),
                localAccounts = localAccounts,
            )
        }

        private fun restrictionsFrom(section: ConfigurationSection): RestrictionToggles = RestrictionToggles(
            blockInteraction = section.getBoolean("block-interaction", true),
            blockBreak = section.getBoolean("block-break", true),
            blockPlace = section.getBoolean("block-place", true),
            itemPickup = section.getBoolean("item-pickup", true),
            itemUse = section.getBoolean("item-use", true),
            containerInteraction = section.getBoolean("container-interaction", true),
            entityDamage = section.getBoolean("entity-damage", true),
            commands = section.getBoolean("commands", true),
            gamemodeChange = section.getBoolean("gamemode-change", true),
            portals = section.getBoolean("portals", true),
            inventoryEdit = section.getBoolean("inventory-edit", true),
            chat = section.getBoolean("chat", true),
        )
    }
}
