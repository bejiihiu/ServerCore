package kz.bejiihiu.servercore.protection

import java.util.logging.Logger
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin

/**
 * op is not an administration mechanism on this server — game accounts must
 * not hold operator status, permissions live in luckperms. this class only
 * audits and strips op, it never grants it.
 */
class OpLockdown(private val plugin: JavaPlugin, private val logger: Logger) {

    data class DeopReport(val removed: Int, val names: List<String>)

    /** startup check: loud warning for every op'd game account */
    fun verifyOps() {
        val ops = plugin.server.operators
        if (ops.isEmpty()) {
            logger.info("op list clean: no game accounts hold operator status")
            return
        }
        for (op in ops) {
            logger.warning(
                "op status detected on game account ${op.name ?: op.uniqueId} (${op.uniqueId}) — " +
                    "run 'servercore deop-all' from console and grant rights through luckperms instead",
            )
        }
    }

    /** strips op from every account on the ops list; safe to call repeatedly */
    fun deopAll(): DeopReport {
        val targets = plugin.server.operators.toList()
        var removed = 0
        val names = mutableListOf<String>()
        for (target in targets) {
            if (!target.isOp) continue
            target.isOp = false
            removed++
            names += target.name ?: target.uniqueId.toString()
        }
        logger.info("deop-all removed operator status from $removed account(s): ${names.joinToString()}")
        return DeopReport(removed, names)
    }
}
