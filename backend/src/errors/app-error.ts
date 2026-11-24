/**
 * base class for typed backend errors.
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
