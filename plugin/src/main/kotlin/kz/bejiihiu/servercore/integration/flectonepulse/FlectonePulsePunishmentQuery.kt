package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.UUID
import java.util.logging.Logger
import net.flectone.pulse.model.entity.FPlayer
import net.flectone.pulse.service.FPlayerService
import net.flectone.pulse.service.ModerationService

/**
 * the real punishment query backed by flectonepulse's public services.
 *
 * strictly read-only: [FPlayerService.getFPlayer] + [ModerationService.getValid].
 * none of the mutation methods on ModerationService (ban/mute/warn/kick/add/
 * remove) are ever called from here — moderation authority stays with
 * flectonepulse, servercore only asks questions.
 *
 * fp may run its lookups on any thread, and flectonepulse events can fire
 * from its own threads too, so the cache in front is concurrent and the fp
 * calls are guarded: a broken fp build degrades to "no info", never crashes
 * a servercore flow.
 */
class FlectonePulsePunishmentQuery(
    private val players: FPlayerService,
    private val moderation: ModerationService,
    private val cache: PunishmentCache,
    private val logger: Logger,
) : PunishmentQuery {

    override fun active(kind: PunishmentKind, uuid: UUID): PunishmentInfo? {
        val cached = cache.get(uuid, kind)
        if (cached is PunishmentCache.Result.Hit) return cached.value

        return runCatching { lookup(kind, uuid) }.fold(
            onSuccess = { info ->
                // verified answer (including "no punishment") is cacheable;
                // the fp event listener keeps it honest via invalidation
                cache.put(uuid, kind, info)
                info
            },
            onFailure = { error ->
                // fp hiccup (db blip, reload race) must not break the caller's
                // flow, but the failure answer must not be cached either: a
                // cached "no punishment" would extend a transient error into a
                // wrong yes/no for the whole ttl even after fp recovers
                logger.warning("flectonepulse punishment lookup failed for $uuid/$kind: ${error.message}")
                null
            },
        )
    }

    private fun lookup(kind: PunishmentKind, uuid: UUID): PunishmentInfo? {
        val fPlayer = players.getFPlayer(uuid)
        // unknown entity means fp has no record of this player (never joined
        // under fp, offline uuid it has not seen) — no punishment data, period
        if (fPlayer.isUnknown()) return null

        val active = moderation.getValid(fPlayer, ModerationMapper.toFpType(kind))
        return active
            .filter { it.isActive() }
            .map { ModerationMapper.toInfo(it, kind) }
            .orElse(null)
    }
}
