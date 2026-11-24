package kz.bejiihiu.servercore.integration.luckperms

import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * the minimal permission-check contract the rest of the plugin codes against.
 *
 * canonical ownership of the servercore.* node constants lives with the
 * protection feature; this file mirrors them so the integration module stands
 * on its own. the merge of the two sides is expected to be mechanical — keep
 * the strings identical when it happens.
 */
fun interface PermissionCheck {

    /**
     * checks a permission node for a player. async-first: the future may be
     * backed by a remote lookup, never block a folia region thread on it.
     */
    fun hasPermission(uuid: UUID, node: String): CompletableFuture<Boolean>
}

/** the servercore permission nodes, one source of truth for every feature */
object ServerCorePermissions {
    const val ADMIN = "servercore.admin"
    const val SPEC = "servercore.spec"
    const val SPEC_OTHER = "servercore.spec.other"
    const val GUEST_MANAGE = "servercore.guest.manage"
    const val ACCOUNT_MANAGE = "servercore.account.manage"
}
