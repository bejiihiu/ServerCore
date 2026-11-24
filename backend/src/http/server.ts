import Fastify from "fastify"
import type { FastifyInstance, FastifyTypeProviderDefault } from "fastify"
import type { IncomingMessage, Server, ServerResponse } from "node:http"
import type { AppConfig } from "../config/env.js"
import type { Logger } from "../logging/logger.js"
import type { GatewayStats } from "../websocket/gateway.js"
import { registerErrorHandler } from "../errors/fastify-error-handler.js"

// concrete instance shape this server builds (pino logger instance is passed in)
type AppInstance = FastifyInstance<
  Server<typeof IncomingMessage, typeof ServerResponse>,
  IncomingMessage,
  ServerResponse<IncomingMessage>,
  Logger,
  FastifyTypeProviderDefault
>

export type HealthSnapshot = {
  uptimeSeconds: number
  mongoConnected: boolean
  gateway: GatewayStats
  connectedServers: string[]
}

/**
 * http plane: health/admin endpoints only. the plugin talks exclusively over
 * the websocket gateway (bun.serve), never through fastify.
 */
export async function buildHttpServer(deps: {
  config: AppConfig
  logger: Logger
  health: () => HealthSnapshot
}): Promise<AppInstance> {
  const app = Fastify({
    loggerInstance: deps.logger,
  })

  registerErrorHandler(app, deps.logger)

  app.get("/health", async (_request, reply) => {
    const snapshot = deps.health()
    // degraded, not dead: keep serving 503 while mongo is down so load
    // balancers pull us out of rotation instead of crash-looping
    reply.code(snapshot.mongoConnected ? 200 : 503).send({
      status: snapshot.mongoConnected ? "ok" : "degraded",
      ...snapshot,
    })
  })

  return app
}
