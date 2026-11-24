import { createHash } from "node:crypto"
import { readFileSync } from "node:fs"
import type { Logger } from "../logging/logger.js"
import type { PayloadOf } from "../websocket/protocol/message-types.js"
import type { ServerConnection } from "../websocket/connection.js"
import { newMessageId } from "../websocket/security/replay-guard.js"

/** the only locale shipped right now; the plugin accepts nothing else */
export const CANONICAL_LOCALE = "ru"

/**
 * the bundle that ships in this image. resolved from the module location, not
 * the process cwd, so `bun run src/app.ts` from anywhere finds it. the docker
 * image copies `src` wholesale, so the file travels with the code.
 */
export const DEFAULT_LOCALE_BUNDLE = new URL("../locales/ru.yml", import.meta.url)

/**
 * the plugin validates every inbound frame against its own packet cap
 * (`backend.max-packet-bytes`, 256 KiB by default). the bundle is a yaml file
 * with a few dozen lines, so crossing this line means something went badly
 * wrong upstream — fail loud instead of pushing a frame the plugin will drop.
 */
export const MAX_BUNDLE_BYTES = 200_000

export type LocaleBundle = {
  locale: string
  /** opaque, derived from content; 16 hex chars of sha256 */
  revision: string
  /** full yaml document, never a diff */
  content: string
}

export type LocaleAckStatus = "applied" | "rejected" | "stale" | "unknown-push"

export type LocaleAckRecord = {
  serverId: string
  status: LocaleAckStatus
  locale: string
  revision: string
  accepted: boolean
  reason?: string
  correlationId?: string
  receivedAt: number
}

type PushRecord = {
  revision: string
  correlationId: string
  sentAt: number
}

/** deterministic revision of the bundle content */
export function revisionOf(content: string): string {
  return createHash("sha256").update(content, "utf8").digest("hex").slice(0, 16)
}

export type LocaleServiceOptions = {
  logger: Logger
  /** override for tests; production uses the shipped bundle */
  bundlePath?: string | URL
  locale?: string
}

/**
 * owns the canonical locale bundle and the push/ack exchange with game
 * servers. the bundle is loaded once at boot: locale files only change with a
 * backend deploy, so there is nothing to watch on disk.
 *
 * acks are tracked per server id (one live connection per server at a time).
 * a reconnect pushes again from `pushTo`, so a lost ack never means a stale
 * plugin forever — the next registration re-delivers the same revision.
 */
export class LocaleService {
  private readonly logger: Logger
  private readonly bundle: LocaleBundle
  private readonly pushes = new Map<string, PushRecord>()
  private readonly acks = new Map<string, LocaleAckRecord>()

  constructor(options: LocaleServiceOptions) {
    this.logger = options.logger
    const locale = options.locale ?? CANONICAL_LOCALE
    const path = options.bundlePath ?? DEFAULT_LOCALE_BUNDLE
    const content = readFileSync(path, "utf8")
    if (content.trim() === "") {
      throw new Error(`locale bundle ${String(path)} is empty`)
    }
    const bytes = Buffer.byteLength(content, "utf8")
    if (bytes > MAX_BUNDLE_BYTES) {
      throw new Error(
        `locale bundle ${String(path)} is ${bytes} bytes, above the ${MAX_BUNDLE_BYTES} byte push ceiling`,
      )
    }
    this.bundle = { locale, revision: revisionOf(content), content }
  }

  get current(): LocaleBundle {
    return this.bundle
  }

  /**
   * pushes the full bundle down an authenticated connection. returns the
   * correlation id the plugin is expected to echo in its LOCALE_ACK, or null
   * when the write was rejected by the socket layer.
   */
  pushTo(connection: ServerConnection): string | null {
    const correlationId = newMessageId()
    const bundle = this.bundle
    this.pushes.set(connection.serverId, {
      revision: bundle.revision,
      correlationId,
      sentAt: Date.now(),
    })
    void connection
      .send(
        "LOCALE_PUSH",
        { locale: bundle.locale, revision: bundle.revision, content: bundle.content },
        { correlationId },
      )
      .catch((error: unknown) => {
        this.logger.warn(
          { err: error, serverId: connection.serverId, revision: bundle.revision },
          "locale push failed",
        )
      })
    return correlationId
  }

  /**
   * records what the plugin reported. the status separates the three
   * interesting cases: the push landed, the plugin refused the file, or the
   * ack answers a revision this backend no longer serves.
   */
  recordAck(
    serverId: string,
    ack: PayloadOf<"LOCALE_ACK">,
    correlationId?: string,
    receivedAt: number = Date.now(),
  ): LocaleAckRecord {
    const push = this.pushes.get(serverId)
    const status: LocaleAckStatus = !ack.accepted
      ? "rejected"
      : push === undefined
        ? "unknown-push"
        : push.revision === ack.revision
          ? "applied"
          : "stale"
    const record: LocaleAckRecord = {
      serverId,
      status,
      locale: ack.locale,
      revision: ack.revision,
      accepted: ack.accepted,
      ...(ack.reason !== undefined ? { reason: ack.reason } : {}),
      ...(correlationId !== undefined ? { correlationId } : {}),
      receivedAt,
    }
    this.acks.set(serverId, record)
    return record
  }

  /** last acknowledged state for a server, if it ever answered */
  ackFor(serverId: string): LocaleAckRecord | undefined {
    return this.acks.get(serverId)
  }
}
