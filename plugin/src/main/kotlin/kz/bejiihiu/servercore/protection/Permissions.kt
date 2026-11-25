package kz.bejiihiu.servercore.protection

/**
 * every permission node servercore hands out, one place so all modules speak
 * the same names. checks always go through the bukkit permissible chain —
 * luckperms owns that chain on this server, op status is never consulted.
 */
object Permissions {
    /** full servercore administration (kept narrow, see the split below) */
    const val ADMIN = "servercore.admin"

    /** enter spectator observation on their own */
    const val SPEC = "servercore.spec"

    /** open spectator sessions on other players */
    const val SPEC_OTHER = "servercore.spec.other"

    /** start/stop guest sessions for others */
    const val GUEST_MANAGE = "servercore.guest.manage"

    /** manage account bindings (link/unlink/delete) */
    const val ACCOUNT_MANAGE = "servercore.account.manage"

    /** toggle the ram info bar on yourself */
    const val BARS_RAM = "servercore.bars.ram"

    /** toggle the ram info bar for other players */
    const val BARS_RAM_OTHER = "servercore.bars.ram.other"

    /** toggle the region tps info bar on yourself */
    const val BARS_TPS = "servercore.bars.tps"

    /** toggle the region tps info bar for other players */
    const val BARS_TPS_OTHER = "servercore.bars.tps.other"

    /** toggle the region mspt info bar on yourself */
    const val BARS_MSPT = "servercore.bars.mspt"

    /** toggle the region mspt info bar for other players */
    const val BARS_MSPT_OTHER = "servercore.bars.mspt.other"

    /** toggle the online players info bar on yourself */
    const val BARS_PLAYERS = "servercore.bars.players"

    /** toggle the online players info bar for other players */
    const val BARS_PLAYERS_OTHER = "servercore.bars.players.other"

    /** toggle the world clock info bar on yourself */
    const val BARS_WORLD = "servercore.bars.world"

    /** toggle the world clock info bar for other players */
    const val BARS_WORLD_OTHER = "servercore.bars.world.other"

    /** toggle the network view bar on yourself (admins/moderators) */
    const val BARS_NET = "servercore.bars.net"

    /** toggle the network view bar for other players */
    const val BARS_NET_OTHER = "servercore.bars.net.other"
}
