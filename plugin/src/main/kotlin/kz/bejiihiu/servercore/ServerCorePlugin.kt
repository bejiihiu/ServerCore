package kz.bejiihiu.servercore

import org.bukkit.plugin.java.JavaPlugin
import kz.bejiihiu.servercore.boot.JoinFlowListener
import kz.bejiihiu.servercore.locale.LocaleService
import kz.bejiihiu.servercore.ui.UiKit

// single entry point, folia-only. no main thread work here, ever
class ServerCorePlugin : JavaPlugin() {

    lateinit var ui: UiKit
        private set

    lateinit var locale: LocaleService
        private set

    override fun onEnable() {
        locale = LocaleService(dataFolder.toPath(), logger)
        ui = UiKit(this, locale)
        server.pluginManager.registerEvents(JoinFlowListener(ui), this)
        logger.info("servercore ${pluginMeta.version} enabled")
    }

    override fun onDisable() {
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
