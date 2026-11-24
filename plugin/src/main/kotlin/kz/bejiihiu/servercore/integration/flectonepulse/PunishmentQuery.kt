package kz.bejiihiu.servercore.integration.flectonepulse

import java.util.UUID
import net.flectone.pulse.model.value.Moderation

/**
 * punishment kinds servercore is allowed to look at. deliberately narrower
 * than flectonepulse's full moderation type set: servercore never issues
 * punishments, it only reads the ones a decision actually needs.
 */
enum class PunishmentKind { BAN, MUTE, WARN, KICK }

/** a read-only snapshot of one active punishment as flectonepulse holds it */
data class PunishmentInfo(
    val kind: PunishmentKind,
    val reason: String?,
    /** permanent punishments never expire */
    val permanent: Boolean,
    /** millis left, 0 for permanent ones */
    val remainingMillis: Long,
    /** when the punishment was issued, epoch millis */
    val issuedAtMillis: Long,
)

/**
 * read-only punishment lookup against flectonepulse. servercore consults this
 * when a decision needs to know about an active ban/mute (guest and admission
 * flows), it never calls anything that creates or removes a punishment —
 * that boundary is flectonepulse's alone.
 */
interface PunishmentQuery {

    /** the active punishment of this kind, or null when the player has none */
    fun active(kind: PunishmentKind, uuid: UUID): PunishmentInfo?

    fun hasActive(kind: PunishmentKind, uuid: UUID): Boolean = active(kind, uuid) != null

    fun activeBan(uuid: UUID): PunishmentInfo? = active(PunishmentKind.BAN, uuid)

    fun activeMute(uuid: UUID): PunishmentInfo? = active(PunishmentKind.MUTE, uuid)

    /** used when flectonepulse is absent: nothing to look up, everything empty */
    object None : PunishmentQuery {
        override fun active(kind: PunishmentKind, uuid: UUID): PunishmentInfo? = null
    }
}

/**
 * flectonepulse [Moderation] records mapped onto servercore's own shape.
 * pure code so the mapping is testable against the real api classes.
 */
object ModerationMapper {

    /** fp type for a servercore kind, used on the way in */
    fun toFpType(kind: PunishmentKind): Moderation.Type = when (kind) {
        PunishmentKind.BAN -> Moderation.Type.BAN
        PunishmentKind.MUTE -> Moderation.Type.MUTE
        PunishmentKind.WARN -> Moderation.Type.WARN
        PunishmentKind.KICK -> Moderation.Type.KICK
    }

    /** records carry both validity and expiry, active() folds them for us */
    fun toInfo(moderation: Moderation, kind: PunishmentKind): PunishmentInfo = PunishmentInfo(
        kind = kind,
        reason = moderation.reason(),
        permanent = moderation.isPermanent(),
        remainingMillis = if (moderation.isPermanent()) 0L else moderation.getRemainingTime(),
        issuedAtMillis = moderation.date(),
    )
}
