package kz.bejiihiu.servercore

import org.bukkit.plugin.java.JavaPlugin

// single entry point, folia-only. no main thread work here, ever
class ServerCorePlugin : JavaPlugin() {

    override fun onEnable() {
        logger.info("servercore ${pluginMeta.version} enabled")
    }

    override fun onDisable() {
        logger.info("servercore ${pluginMeta.version} disabled, bye")
    }
}
