import { Types } from "mongoose"
import { ApplicationModel } from "../../database/models/application.js"
import { AuditLogModel } from "../../database/models/audit-log.js"
import { GroupInviteModel } from "../../database/models/group-invite.js"
import { GuestSnapshotModel } from "../../database/models/guest-snapshot.js"
import { PlayerSessionModel } from "../../database/models/session.js"
import { ServerEventModel } from "../../database/models/server-event.js"
import { VotingMessageModel } from "../../database/models/voting-message.js"
import { VoteModel } from "../../database/models/vote.js"
import type {
  ApplicationPurgePort,
  AuditLogPurgePort,
  GroupInvitePurgePort,
  GuestSnapshotPurgePort,
  ServerEventPurgePort,
  SessionPurgePort,
  VotePurgePort,
  VotingMessagePurgePort,
} from "./retention-service.js"

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

export class MongoVotingMessagePurge implements VotingMessagePurgePort {
  async deleteDeadOlderThan(cutoff: Date): Promise<number> {
    // keep the keyboards of every live voting and of applications decided no
    // earlier than the cutoff; what's left is either an old decision or an
    // orphan whose application row is gone (crash between decide and cleanup)
    const keep = (await ApplicationModel.distinct("_id", {
      $or: [
        { status: { $in: ["PENDING", "VOTING"] } },
        { "voting.decidedAt": { $gte: cutoff } },
      ],
    })) as Types.ObjectId[]
    const result = await VotingMessageModel.deleteMany({ applicationId: { $nin: keep } })
    return result.deletedCount ?? 0
  }
}

export class MongoVotePurge implements VotePurgePort {
  async deleteDecidedOlderThan(cutoff: Date): Promise<number> {
    // same keep-set as the messages: live votings and recent decisions
    const keep = (await ApplicationModel.distinct("_id", {
      $or: [
        { status: { $in: ["PENDING", "VOTING"] } },
        { "voting.decidedAt": { $gte: cutoff } },
      ],
    })) as Types.ObjectId[]
    const result = await VoteModel.deleteMany({ applicationId: { $nin: keep } })
    return result.deletedCount ?? 0
  }
}

export class MongoGroupInvitePurge implements GroupInvitePurgePort {
  async deleteDeadOlderThan(cutoff: Date, usedCutoff: Date): Promise<number> {
    // three ways an invite record becomes dead: revoked long ago, never used
    // and expired long ago (telegram-side expiry passed ages ago), or used so
    // long ago that it no longer proves anything
    const result = await GroupInviteModel.deleteMany({
      $or: [
        { status: "REVOKED", issuedAt: { $lt: cutoff } },
        { status: "ISSUED", expiresAt: { $lt: cutoff } },
        { status: "USED", issuedAt: { $lt: usedCutoff } },
      ],
    })
    return result.deletedCount ?? 0
  }
}

export class MongoApplicationPurge implements ApplicationPurgePort {
  async deleteClosedOlderThan(cutoff: Date): Promise<number> {
    // only dead ends; an approved application is the admission record behind
    // a live account and is kept
    const result = await ApplicationModel.deleteMany({
      status: { $in: ["REJECTED", "EXPIRED"] },
      "voting.decidedAt": { $lt: cutoff, $ne: null },
    })
    return result.deletedCount ?? 0
  }
}
