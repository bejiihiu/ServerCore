import mongoose from "mongoose"

export type CollectionStat = {
  name: string
  /** real document count (countDocuments, exact but ok at these sizes) */
  documents: number
  /** estimated storage bytes from $collStats, null when the deployment refuses */
  storageBytes: number | null
}

export type CollectionStatsReport = {
  collections: CollectionStat[]
  /** sum of storage where known, null when any count is unknown */
  totalStorageBytes: number | null
}

export interface CollectionStatsSource {
  collect(): Promise<CollectionStatsReport>
}

/** every collection the backend owns; single source for the /stats output */
export const TRACKED_COLLECTIONS = [
  "users",
  "applications",
  "votes",
  "sessions",
  "auth_requests",
  "telegram_links",
  "guest_snapshots",
  "punishments_external",
  "audit_logs",
  "server_events",
] as const

/**
 * real numbers off the live mongo deployment. counts via countDocuments,
 * sizes via the $collStats aggregation; atlas m0 serves both. a denied
 * $collStats degrades storageBytes to null instead of faking a number.
 *
 * storage view only: filtered domain counters (active users, open votings)
 * live in services/stats-counts.ts and feed the same /stats screen.
 */
export class MongoCollectionStats implements CollectionStatsSource {
  async collect(): Promise<CollectionStatsReport> {
    const db = mongoose.connection.db
    if (db === undefined) throw new Error("mongo not connected")

    const collections: CollectionStat[] = []
    for (const name of TRACKED_COLLECTIONS) {
      const count = await db.collection(name).countDocuments()
      let storageBytes: number | null = null
      try {
        const stats = await db.command({ collStats: name })
        if (typeof stats === "object" && stats !== null && "storageSize" in stats) {
          storageBytes = Number((stats as { storageSize: unknown }).storageSize)
        }
      } catch {
        // deployment refuses collStats: report the count, leave size unknown
      }
      collections.push({ name, documents: count, storageBytes })
    }

    const knownSizes = collections.map((c) => c.storageBytes).filter((size): size is number => size !== null)
    return {
      collections,
      totalStorageBytes: knownSizes.length === collections.length ? knownSizes.reduce((a, b) => a + b, 0) : null,
    }
  }
}
