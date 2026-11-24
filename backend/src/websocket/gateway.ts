import { verifyHandshake, type NonceCache } from "./auth.js"
import { ServerConnection, type ConnectionOptions, type GatewaySocketData } from "./connection.js"
import type { HandlerRegistry } from "./handlers/registry.js"
import type { MessageType, PayloadOf } from "./protocol/message-types.js"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"

export type GatewayStats = {
  running: boolean
  connections: number
}

export type Gateway = {
  readonly port: number
  readonly stats: GatewayStats
  readonly connectedServers: string[]
  get(serverId: string): ServerConnection | null
  sendTo<T extends Exclude<MessageType, "SERVER_HELLO" | "SERVER_READY">>(
    serverId: string,
    type: T,
    payload: PayloadOf<T>,
    options?: { awaitAck?: boolean },
  ): Promise<void>
  stop(): void
}

/**
 * plugin-facing websocket gateway on native Bun.serve.
 *
 * connection auth happens in the fetch handler BEFORE upgrade (hmac over
 * server|ts|nonce against the per-server secret) - an unauthenticated client
 * never becomes a socket at all. after the upgrade every frame is additionally
 * envelope-signed with the same secret, so the two layers are independent.
 *
 * fastify (http plane) runs separately on its own port; the plugin never
 * talks to it.
 */
export function createGateway(config: AppConfig, logger: Logger, handlers: HandlerRegistry): Gateway {
  const nonces: NonceCache = new Map()
  const connections = new Map<string, ServerConnection>()
  const stats: GatewayStats = { running: true, connections: 0 }

  const baseOptions: Omit<ConnectionOptions, "secret"> = {
    heartbeatIntervalMs: config.wsHeartbeatIntervalMs,
    heartbeatTimeoutMs: config.wsHeartbeatTimeoutMs,
    maxPacketBytes: config.wsMaxPacketBytes,
    replayWindowMs: config.wsReplayWindowMs,
    replayGuardTtlMs: config.wsReplayWindowMs + config.wsNonceTtlMs,
  }

  const server = Bun.serve<GatewaySocketData>({
    port: config.wsPort,
    hostname: config.host,

    async fetch(req, requestServer) {
      const url = new URL(req.url)
      if (url.pathname !== "/ws") return new Response("not found", { status: 404 })
      if (req.method !== "GET") return new Response("method not allowed", { status: 405 })

      const result = verifyHandshake(
        {
          server: url.searchParams.get("server") ?? undefined,
          ts: url.searchParams.get("ts") ?? undefined,
          nonce: url.searchParams.get("nonce") ?? undefined,
          sig: url.searchParams.get("sig") ?? undefined,
        },
        config.serverCredentials,
        nonces,
        Date.now(),
        config.wsReplayWindowMs,
        config.wsNonceTtlMs,
      )
      if (!result.ok) {
        logger.warn({ reason: result.reason, ip: requestServer.requestIP(req)?.address }, "gateway handshake rejected")
        return new Response("unauthorized", { status: 401 })
      }

      const upgraded = requestServer.upgrade(req, {
        data: { serverId: result.serverId, connectedAt: Date.now() },
      })
      return upgraded ? undefined : new Response("upgrade failed", { status: 400 })
    },

    websocket: {
      open(ws) {
        const secret = config.serverCredentials[ws.data.serverId]
        if (secret === undefined) {
          // can't happen after the pre-upgrade check, belt and braces
          ws.close(1011, "credentials unavailable")
          return
        }

        const connection = new ServerConnection(ws.data.serverId, ws, { ...baseOptions, secret }, logger)
        ws.data.connection = connection
        stats.connections += 1

        // a reconnecting server replaces its stale connection
        const previous = connections.get(ws.data.serverId)
        if (previous !== undefined && previous !== connection) {
          logger.info({ serverId: ws.data.serverId }, "replacing stale connection for server")
          previous.close(1000, "replaced by new connection")
        }
        connections.set(ws.data.serverId, connection)
        connection.start()
        logger.info({ serverId: ws.data.serverId }, "game server connected, awaiting hello")
      },

      message(ws, raw) {
        const connection = ws.data.connection
        if (connection === undefined) {
          ws.close(1011, "no connection state")
          return
        }

        const text = typeof raw === "string" ? raw : new TextDecoder().decode(raw)
        const failure = connection.handleFrame(text, Date.now(), (envelope, conn) => handlers.dispatch(envelope, conn))
        if (failure !== null) {
          logger.warn({ serverId: connection.serverId, failure }, "protocol violation")
          connection.send("ERROR", { code: failure.reason, message: failure.detail ?? failure.reason })
          connection.close(1008, "protocol violation")
          if (connections.get(connection.serverId) === connection) {
            connections.delete(connection.serverId)
          }
        }
      },

      pong(ws) {
        ws.data.connection?.onPong()
      },

      close(ws) {
        stats.connections -= 1
        const connection = ws.data.connection
        if (connection !== undefined && connections.get(connection.serverId) === connection) {
          logger.info({ serverId: connection.serverId }, "game server disconnected")
          connections.delete(connection.serverId)
        }
        connection?.close(1000, "socket closed")
      },
    },
  })

  logger.info({ port: server.port }, "websocket gateway listening")

  return {
    port: server.port,
    stats,
    get connectedServers(): string[] {
      return [...connections.keys()]
    },
    get(serverId: string): ServerConnection | null {
      return connections.get(serverId) ?? null
    },
    async sendTo<T extends Exclude<MessageType, "SERVER_HELLO" | "SERVER_READY">>(
      serverId: string,
      type: T,
      payload: PayloadOf<T>,
      options: { awaitAck?: boolean } = {},
    ): Promise<void> {
      const connection = connections.get(serverId)
      if (connection === undefined) throw new Error(`no connection for server ${serverId}`)
      await connection.send(type, payload, { awaitAck: options.awaitAck ?? true })
    },
    stop(): void {
      stats.running = false
      for (const [, connection] of connections) {
        connection.close(1001, "server shutting down")
      }
      connections.clear()
      server.stop(true)
      logger.info("websocket gateway stopped")
    },
  }
}
