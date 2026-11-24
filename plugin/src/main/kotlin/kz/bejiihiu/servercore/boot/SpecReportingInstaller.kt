package kz.bejiihiu.servercore.boot

import kz.bejiihiu.servercore.spec.SpecAuditTrail
import kz.bejiihiu.servercore.spec.SpecEventSender
import kz.bejiihiu.servercore.spec.SpecSessionRegistry
import kz.bejiihiu.servercore.websocket.MessageType
import kz.bejiihiu.servercore.websocket.Payload

/**
 * Spec reporting bridge: every settled session start/end goes to the backend
 * over the websocket (the backend writes the audit_logs rows), and the local
 * audit trail keeps the same events as a daily-rotating jsonl.
 *
 * This has to sit between the transport and the spec command stack: the sender
 * speaks through the gateway client, and the registry feeds both the status
 * frames and the session machine.
 */
class SpecReportingInstaller(
    private val ctx: PluginContext,
    private val gateway: GatewayInstaller,
) {

    var trail: SpecAuditTrail? = null
        private set

    lateinit var registry: SpecSessionRegistry
        private set

    fun install() {
        val config = ctx.pluginConfig.specAudit
        val trail = if (config.enabled) {
            SpecAuditTrail(
                directory = ctx.dataFolder.toPath().resolve(config.directory),
                io = ctx.io,
                retentionDays = config.retentionDays.toLong(),
                maxEntriesPerSession = config.maxEntriesPerSession,
                queueCapacity = config.queueCapacity,
            )
        } else null
        this.trail = trail

        val client = gateway.client
        val sender = object : SpecEventSender {
            override fun sendSpecStarted(payload: Payload.SpecStarted) {
                client.send(MessageType.SPEC_STARTED, payload)
            }

            override fun sendSpecEnded(payload: Payload.SpecEnded) {
                client.send(MessageType.SPEC_ENDED, payload)
            }
        }
        registry = SpecSessionRegistry(sender, trail)
        ctx.activeSpecSessions = { registry.activeCount() }
    }

    /** Flushes synchronously, before the io pool goes away. */
    fun stop() {
        trail?.close()
    }
}
