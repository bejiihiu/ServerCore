package kz.bejiihiu.servercore.bars

import java.util.Locale
import kz.bejiihiu.servercore.protection.Permissions

/**
 * the bars this plugin can show. names are purpur-compatible on purpose:
 * /rambar and /tpsbar are muscle memory for anyone coming from purpur.
 */
enum class BarId(
    val key: String,
    val permission: String,
    val otherPermission: String,
) {
    RAM("ram", Permissions.BARS_RAM, Permissions.BARS_RAM_OTHER),
    TPS("tps", Permissions.BARS_TPS, Permissions.BARS_TPS_OTHER),
    MSPT("mspt", Permissions.BARS_MSPT, Permissions.BARS_MSPT_OTHER),
    PLAYERS("players", Permissions.BARS_PLAYERS, Permissions.BARS_PLAYERS_OTHER),
    WORLD("world", Permissions.BARS_WORLD, Permissions.BARS_WORLD_OTHER),
    NET("net", Permissions.BARS_NET, Permissions.BARS_NET_OTHER),
    ;

    /** locale key of the bar title, e.g. bars.ram.title */
    val titleKey: String get() = "bars.$key.title"

    /** command literal, e.g. rambar */
    val commandName: String get() = "${key}bar"

    /** the label shown in toggle feedback; universal, no translation needed */
    val label: String get() = key.uppercase(Locale.ROOT)
}

/** where a bar renders. one active bar per surface per player. */
enum class BarSurface(val key: String) {
    BOSSBAR("bossbar"),
    ACTIONBAR("actionbar"),
    ;

    companion object {
        /** null for an unknown name; the loader falls back and warns */
        fun parse(raw: String): BarSurface? =
            entries.firstOrNull { it.key.equals(raw.trim(), ignoreCase = true) }
    }
}
