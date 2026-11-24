import type { FastifyReply, FastifyRequest } from "fastify"
import { ZodError } from "zod"
import { AppError } from "./app-error.js"
import type { Logger } from "../logging/logger.js"

type FastifyValidationLike = {
  code?: unknown
  statusCode?: number
  validation?: unknown
}

function isFastifyValidation(error: unknown): boolean {
  if (typeof error !== "object" || error === null) return false
  const candidate = error as FastifyValidationLike
  return candidate.validation !== undefined || candidate.code === "FST_ERR_VALIDATION"
}

/**
 * structural fastify-like surface so the handler works with any fastify
 * instance generic (the concrete instance types vary with the logger class).
 */
type FastifyLike = {
  setErrorHandler(handler: (error: unknown, request: FastifyRequest, reply: FastifyReply) => unknown): unknown
  setNotFoundHandler(handler: (request: FastifyRequest, reply: FastifyReply) => unknown): unknown
}

export function registerErrorHandler(app: FastifyLike, logger: Logger): void {
  app.setErrorHandler((error: unknown, request: FastifyRequest, reply: FastifyReply) => {
    if (error instanceof AppError) {
      reply.status(error.statusCode).send({
        statusCode: error.statusCode,
        code: error.code,
        message: error.message,
        details: error.details,
      })
      return
    }

    if (error instanceof ZodError) {
      reply.status(400).send({
        statusCode: 400,
        code: "VALIDATION_ERROR",
        message: "request payload failed validation",
        details: { issues: error.issues },
      })
      return
    }

    if (isFastifyValidation(error)) {
      reply.status(400).send({
        statusCode: 400,
        code: "VALIDATION_ERROR",
        message: "request payload failed validation",
      })
      return
    }

    logger.error({ err: error, url: request.url }, "unhandled request error")
    reply.status(500).send({
      statusCode: 500,
      code: "INTERNAL_ERROR",
      message: "internal server error",
    })
  })

  app.setNotFoundHandler((_request, reply) => {
    reply.status(404).send({
      statusCode: 404,
      code: "NOT_FOUND",
      message: "route not found",
    })
  })
}
