package kz.bejiihiu.servercore.ops

/**
 * ru/en texts the plugin shows to players during restarts. same key-map idea
 * as the backend i18n, keys namespaced ops.*; locale comes from the plugin
 * config. unknown key/locale falls back to en, then to the key itself.
 */
object OpsText {

    const val KEY_RESTART_TITLE = "ops.restart.title"
    const val KEY_RESTART_COUNTDOWN = "ops.restart.countdown"
    const val KEY_RESTART_ACTIONBAR = "ops.restart.actionbar"
    const val KEY_RESTART_KICK = "ops.restart.kick"

    private const val RU = "ru"
    private const val EN = "en"

    private val messages: Map<String, Map<String, String>> = mapOf(
        KEY_RESTART_TITLE to mapOf(RU to "РЕСТАРТ", EN to "RESTART"),
        KEY_RESTART_COUNTDOWN to mapOf(RU to "через {seconds} сек.", EN to "in {seconds}s"),
        KEY_RESTART_ACTIONBAR to mapOf(
            RU to "Сервер перезапускается — сохраните прогресс",
            EN to "Server is restarting — save your progress",
        ),
        KEY_RESTART_KICK to mapOf(
            RU to "Сервер перезапускается. Возвращайтесь через пару минут!",
            EN to "The server is restarting. Come back in a couple of minutes!",
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
