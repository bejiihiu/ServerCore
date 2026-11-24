package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.guest.GuestConfig
import kz.bejiihiu.servercore.util.I18n

/**
 * Loads the guest config and the message layer.
 *
 * Both live here because the guest config carries the language pick, and
 * everything that renders text afterwards — luckperms debug output, the guest
 * flow, protection denials, the spec command, placeholders — reads the same
 * i18n instance, so it has to exist before those installers run.
 */
class I18nInstaller(private val ctx: PluginContext) {

    fun install() {
        ctx.guestConfig = GuestConfig.load(ctx.plugin.config, ctx.logger)
        ctx.i18n = I18n(ctx.locale, ctx.guestConfig.lang)
    }
}
