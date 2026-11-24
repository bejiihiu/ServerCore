package kz.bejiihiu.servercore.sync

import java.util.UUID

/**
 * recording [ResyncExecutor] for pure-logic tests: captures every call in
 * order and returns a configurable applied state, no packets involved.
 */
class RecordingResync(
    var appliedState: ViewState? = ViewState.VISIBLE,
) : ResyncExecutor {

    val calls = mutableListOf<String>()

    private fun record(op: String, detail: String) {
        calls.add("$op($detail)")
    }

    override fun resetViewer(viewer: UUID) = record("resetViewer", "$viewer")

    override fun resyncViewer(viewer: UUID) = record("resyncViewer", "$viewer")

    override fun resetTarget(viewer: UUID, target: UUID) = record("resetTarget", "$viewer,$target")

    override fun resyncTarget(viewer: UUID, target: UUID): ViewState? {
        record("resyncTarget", "$viewer,$target")
        return appliedState
    }

    override fun restoreVanilla(viewer: UUID, target: UUID) = record("restoreVanilla", "$viewer,$target")

    /** ordered operation names without arguments */
    fun ops(): List<String> = calls.map { it.substringBefore('(') }

    /** full ordered call log */
    fun log(): List<String> = calls.toList()

    fun clear() = calls.clear()
}
