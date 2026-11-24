import type { RetentionStore } from "./persistence.js"
import type { Logger } from "../../logging/logger.js"

export type RetentionConfig = {
  /** rolling retention window for closed/old game sessions, days */
  sessionRetentionDays: number
}

/** store surface the retention task needs; implemented by both store twins */
export type { RetentionStore }

export type CollectionSizes = Record<string, number>

/** provider of live collection sizes for the admin /stats line */
export type CollectionSizesProvider = () => Promise<CollectionSizes>

const DAY_MS = 86_400_000

/**
 * daily data hygiene: sessions past their rolling retention window get
 * purged, one open session per player is always kept by the store itself.
 * runs once a day from the app wiring; every invocation is idempotent.
 */
export class RetentionService {
  private readonly store: RetentionStore
  private readonly config: RetentionConfig
  private readonly logger: Logger

  constructor(deps: { store: RetentionStore; config: RetentionConfig; logger: Logger }) {
    this.store = deps.store
    this.config = deps.config
    this.logger = deps.logger
  }

  /** removes sessions closed (or started) more than N days ago, returns count */
  async run(now = Date.now()): Promise<number> {
    const cutoff = now - this.config.sessionRetentionDays * DAY_MS
    const purged = await this.store.purgeSessionsOlderThan(cutoff)
    if (purged > 0) {
      this.logger.info({ purged, retentionDays: this.config.sessionRetentionDays }, "session retention purge done")
    }
    return purged
  }
}

/**
 * real collection sizes for /stats. estimatedDocumentCount is a metadata
 * read, cheap enough for the free atlas tier.
 */
export function mongoCollectionSizes(models: { name: string; estimatedDocumentCount(): Promise<number> }[]): CollectionSizesProvider {
  return async () => {
    const sizes: CollectionSizes = {}
    for (const model of models) {
      try {
        sizes[model.name] = await model.estimatedDocumentCount()
      } catch {
        // a db blip must not kill the stats command; the entry is just absent
      }
    }
    return sizes
  }
}
