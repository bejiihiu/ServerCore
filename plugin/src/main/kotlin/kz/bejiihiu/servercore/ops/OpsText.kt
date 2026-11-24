package kz.bejiihiu.servercore.ops

/**
 * ru/en texts the plugin shows to players during restarts. same key-map idea
 * as the backend i18n, keys namespaced ops.*; locale comes from the plugin
 * config. unknown key/locale falls back to en, then to the key itself.
 *
 * templates are miniMessage with the plugin theme tags, rendered through the
 * theme resolver — the restart screen is the loudest surface we own, it must
 * not be the one place with vanilla colors and a different title rhythm.
 */
object OpsText {

    const val KEY_RESTART_TITLE = "ops.restart.title"
    const val KEY_RESTART_COUNTDOWN = "ops.restart.countdown"
    const val KEY_RESTART_BAR = "ops.restart.bar"
    const val KEY_RESTART_KICK = "ops.restart.kick"

    private const val RU = "ru"
    private const val EN = "en"

    private val messages: Map<String, Map<String, String>> = mapOf(
        KEY_RESTART_TITLE to mapOf(
            RU to "<gold>перезапуск</gold>",
            EN to "<gold>restart</gold>",
        ),
        KEY_RESTART_COUNTDOWN to mapOf(
            RU to "<secondary>осталось <gold>{seconds}</gold> с</secondary>",
            EN to "<secondary><gold>{seconds}</gold>s left</secondary>",
        ),
        KEY_RESTART_BAR to mapOf(
            RU to "<secondary>перезапуск сервера</secondary> <muted>·</muted> <warn>прогресс сохранится</warn>",
            EN to "<secondary>server restart</secondary> <muted>·</muted> <warn>your progress is saved</warn>",
        ),
        KEY_RESTART_KICK to mapOf(
            RU to "<primary>перезапуск</primary> <muted>·</muted> <secondary>сервер вернётся через пару минут</secondary>",
            EN to "<primary>restart</primary> <muted>·</muted> <secondary>back in a couple of minutes</secondary>",
        ),
    )

    fun text(locale: String, key: String, params: Map<String, String> = emptyMap()): String {
        val entry = messages[key] ?: return key
        var result = entry[locale] ?: entry[EN] ?: key
        for ((name, value) in params) {
            result = result.replace("{$name}", value)
        }
        return result
    }
}
