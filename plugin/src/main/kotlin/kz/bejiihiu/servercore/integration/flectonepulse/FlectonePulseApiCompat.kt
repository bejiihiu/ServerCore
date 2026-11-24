package kz.bejiihiu.servercore.integration.flectonepulse

/**
 * runtime version guard for the flectonepulse api artifact.
 *
 * the plugin builds against net.flectone.pulse:api 1.15.2. at runtime the
 * server may carry an older or newer build whose public surface moved, so
 * before touching anything we probe the sentinel classes every supported
 * call path needs. one missing class = incompatible artifact: degrade with a
 * precise message instead of a NoClassDefFoundError storm later.
 */
object FlectonePulseApiCompat {

    /** api version this plugin was compiled against */
    const val TARGET_VERSION = "1.15.2"

    /** every public api class the integration actually touches */
    val REQUIRED_CLASSES: List<String> = listOf(
        "net.flectone.pulse.FlectonePulseAPI",
        "net.flectone.pulse.FlectonePulse",
        "net.flectone.pulse.annotation.Pulse",
        "net.flectone.pulse.listener.PulseListener",
        "net.flectone.pulse.platform.registry.ListenerRegistry",
        "net.flectone.pulse.service.ModerationService",
        "net.flectone.pulse.service.FPlayerService",
        "net.flectone.pulse.model.value.Moderation",
        "net.flectone.pulse.model.entity.FPlayer",
        "net.flectone.pulse.module.integration.telegram.TelegramModule",
    )

    /**
     * returns the first required class the probe cannot see, or null when
     * the artifact surface matches. the probe is injected so tests can fake
     * a classloader without shipping a real flectonepulse jar.
     */
    fun firstMissingClass(probe: (String) -> Boolean): String? =
        REQUIRED_CLASSES.firstOrNull { !probe(it) }
}
