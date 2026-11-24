package kz.bejiihiu.servercore.guest

/**
 * pure decision helpers for guest restrictions, kept free of bukkit types so
 * they are unit-testable.
 */
object CommandGate {

    /** "/foo:bar baz" -> "bar", "/help" -> "help", garbage tolerated */
    fun extract(commandLine: String): String? {
        val trimmed = commandLine.trim().removePrefix("/")
        if (trimmed.isEmpty()) return null
        val head = trimmed.split(' ', limit = 2).first()
        // namespaced form "plugin:command" counts as the bare command
        return head.substringAfterLast(':').lowercase().ifEmpty { null }
    }

    fun isAllowed(commandLine: String, whitelist: Set<String>): Boolean {
        val command = extract(commandLine) ?: return false
        return command.lowercase() in whitelist
    }
}
