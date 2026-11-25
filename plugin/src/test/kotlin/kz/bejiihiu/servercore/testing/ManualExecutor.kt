package kz.bejiihiu.servercore.testing

import java.util.Collections
import java.util.concurrent.Executor

/**
 * executor that only collects runnables, so a test can decide when and in
 * which order async work actually lands. virtual threads run queued work in
 * whatever order the scheduler picks, which is exactly the chaos a durable
 * file has to survive; this makes that chaos reproducible.
 */
class ManualExecutor : Executor {
    private val pending = Collections.synchronizedList(mutableListOf<Runnable>())

    override fun execute(command: Runnable) {
        pending.add(command)
    }

    fun queued(): Int = pending.size

    fun drainInOrder() = drain(reversed = false)

    fun drainInReverse() = drain(reversed = true)

    private fun drain(reversed: Boolean) {
        val copy = synchronized(pending) {
            ArrayList(pending).also { pending.clear() }
        }
        (if (reversed) copy.reversed() else copy).forEach { it.run() }
    }
}
