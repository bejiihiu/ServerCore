/**
 * base class for all typed backend errors.
 * every error carries a stable machine code + http status so the fastify
 * error handler can map it cleanly.
 */
export class AppError extends Error {
  readonly code: string
  readonly statusCode: number
  readonly details?: Record<string, unknown>

  constructor(code: string, statusCode: number, message: string, details?: Record<string, unknown>) {
    super(message)
    this.name = new.target.name
    this.code = code
    this.statusCode = statusCode
    this.details = details
  }
}

export class ValidationError extends AppError {
  constructor(message: string, details?: Record<string, unknown>) {
    super("VALIDATION_ERROR", 400, message, details)
  }
}

export class UnauthorizedError extends AppError {
  constructor(message = "unauthorized") {
    super("UNAUTHORIZED", 401, message)
  }
}

export class NotFoundError extends AppError {
  constructor(resource: string) {
    super("NOT_FOUND", 404, `${resource} not found`)
  }
}

export class ConflictError extends AppError {
  constructor(message: string, details?: Record<string, unknown>) {
    super("CONFLICT", 409, message, details)
  }
}

/** a websocket peer sent something that violates the protocol. connection must die. */
export class ProtocolError extends AppError {
  constructor(message: string, details?: Record<string, unknown>) {
    super("PROTOCOL_VIOLATION", 400, message, details)
  }
}

/** config is broken beyond what zod already catches (missing secrets, etc). */
export class ConfigError extends AppError {
  constructor(message: string) {
    super("CONFIG_ERROR", 500, message)
  }
}
