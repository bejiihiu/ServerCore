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
}
