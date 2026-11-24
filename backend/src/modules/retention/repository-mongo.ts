import { AuditLogModel } from "../../database/models/audit-log.js"
import { GuestSnapshotModel } from "../../database/models/guest-snapshot.js"
import { PlayerSessionModel } from "../../database/models/session.js"
import { ServerEventModel } from "../../database/models/server-event.js"
import type { AuditLogPurgePort, GuestSnapshotPurgePort, ServerEventPurgePort, SessionPurgePort } from "./retention-service.js"

export class MongoAuditLogPurge implements AuditLogPurgePort {
  async deleteOlderThan(cutoff: Date): Promise<number> {
    const result = await AuditLogModel.deleteMany({ createdAt: { $lt: cutoff } })
    return result.deletedCount ?? 0
  }
}

export class MongoServerEventPurge implements ServerEventPurgePort {
  async deleteOlderThan(cutoff: Date): Promise<number> {
    const result = await ServerEventModel.deleteMany({ receivedAt: { $lt: cutoff } })
    return result.deletedCount ?? 0
  }
}

export class MongoSessionPurge implements SessionPurgePort {
  async deleteClosedOlderThan(cutoff: Date): Promise<number> {
    // only closed sessions: endedAt set or status DISCONNECTED
    const result = await PlayerSessionModel.deleteMany({
      endedAt: { $lt: cutoff, $ne: null },
    })
    return result.deletedCount ?? 0
  }

  async pruneDuplicateActive(): Promise<number> {
    // keep the newest open session per player, close+delete the stragglers.
    // group by uuid over open sessions, mark all but the newest as
    // DISCONNECTED (they're stale by definition), then delete the closed ones
    const stale = await PlayerSessionModel.aggregate<{ _id: string; keepId: string; staleIds: string[] }>([
      { $match: { endedAt: null } },
      { $sort: { startedAt: -1 } },
      {
        $group: {
          _id: "$minecraftUuid",
          keepId: { $first: "$_id" },
          staleIds: { $push: "$_id" },
        },
      },
      { $match: { $expr: { $gt: [{ $size: "$staleIds" }, 1] } } },
    ])
    if (stale.length === 0) return 0

    let deleted = 0
    for (const group of stale) {
      const staleIds = group.staleIds.filter((id) => id !== group.keepId)
      const result = await PlayerSessionModel.deleteMany({ _id: { $in: staleIds } })
      deleted += result.deletedCount ?? 0
    }
    return deleted
  }
}

export class MongoGuestSnapshotPurge implements GuestSnapshotPurgePort {
  async deleteOrphanedOlderThan(cutoff: Date): Promise<number> {
    // orphaned = the uuid has no open session anymore (session ended or the
    // player got deleted). the snapshot would never be restored, it's dead
    // weight on a 512mb tier
    const openUuids = (await PlayerSessionModel.distinct("minecraftUuid", { endedAt: null })) as string[]
    const result = await GuestSnapshotModel.deleteMany({
      createdAt: { $lt: cutoff },
      minecraftUuid: { $nin: openUuids },
    })
    return result.deletedCount ?? 0
  }
}
