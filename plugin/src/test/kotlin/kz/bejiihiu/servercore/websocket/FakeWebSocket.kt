package kz.bejiihiu.servercore.websocket

import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

/**
 * test stand-in for the jdk websocket: records writes, optionally fails them,
 * no networking involved.
 */
class FakeWebSocket(private val failSends: Boolean = false) : WebSocket {
    val sent = mutableListOf<String>()
    var aborted = false
    var closeRequested = false

    private fun completed(): CompletableFuture<WebSocket> = CompletableFuture.completedFuture(this)

    override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
        if (failSends) return CompletableFuture.failedFuture(RuntimeException("broken pipe"))
        sent.add(data.toString())
        return completed()
    }

    override fun sendBinary(data: ByteBuffer, last: Boolean): CompletableFuture<WebSocket> = completed()
    override fun sendPing(message: ByteBuffer): CompletableFuture<WebSocket> = completed()
    override fun sendPong(message: ByteBuffer): CompletableFuture<WebSocket> = completed()
    override fun sendClose(statusCode: Int, reason: String?): CompletableFuture<WebSocket> {
        closeRequested = true
        return completed()
    }
    override fun request(n: Long) {}
    override fun getSubprotocol(): String = ""
    override fun isOutputClosed(): Boolean = false
    override fun isInputClosed(): Boolean = false
    override fun abort() {
        aborted = true
    }
}
