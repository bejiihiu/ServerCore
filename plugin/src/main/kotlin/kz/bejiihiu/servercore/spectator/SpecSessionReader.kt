package kz.bejiihiu.servercore.spectator

import java.util.Optional
import java.util.UUID

/**
 * read-side contract between the live inventory viewer (this package) and the
 * spec session machinery, which lives on its own branch. the session side owns
 * the canonical definition and implementation; this copy exists so both sides
 * compile standalone and the merge stays mechanical — keep the two definitions
 * identical, anything beyond this file belongs to the session owner.
 *
 * semantics: returns the target of the spec session this reader is bound to,
 * or [Optional.empty] when no session is active. implementations must be safe
 * to call from any thread (folia global/region/async/netty) and must never
 * block.
 */
interface SpecSessionReader {
    fun currentTarget(): Optional<UUID>
}
