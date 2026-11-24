import { AuditLogModel } from "../../database/models/audit-log.js"
import { AuthRequestModel } from "../../database/models/auth-request.js"
import { PlayerSessionModel, type PlayerSession } from "../../database/models/session.js"
import { TelegramLinkModel, type TelegramLink } from "../../database/models/telegram-link.js"
import { UserModel, type User } from "../../database/models/user.js"
import { DuplicateKeyError, type AuditRepository, type AuthRequestRepository, type AuditRecord, type LinkRecord, type SessionRecord, type SessionRepository, type TelegramLinkRepository, type UserRecord, type UserRepository } from "./repository.js"

const DUPLICATE_KEY_CODE = 11000

type WithMaybeCode = { code?: number }

/** maps a mongoose duplicate-key error onto the storage-neutral one */
function mapDuplicate(err: unknown, field: string): unknown {
  if (typeof err === "object" && err !== null && (err as WithMaybeCode).code === DUPLICATE_KEY_CODE) {
    return new DuplicateKeyError(field)
  }
  return err
}

// the mongoose interfaces use nested { minecraft, telegram } while the service
// layer wants flat records; these two mappers are the only place that knows
// about both shapes
function toUserRecord(doc: User): UserRecord {
  return {
    minecraftUuid: doc.minecraft.uuid,
    username: doc.minecraft.username,
    telegramId: doc.telegram.id,
    telegramUsername: doc.telegram.username,
    telegramLinkedAt: doc.telegram.linkedAt,
    status: doc.status,
    language: doc.language,
    lastApplicationAt: doc.lastApplicationAt,
    createdAt: doc.createdAt,
    updatedAt: doc.updatedAt,
  }
}

function toLinkRecord(doc: TelegramLink): LinkRecord {
  return {
    telegramId: doc.telegramId,
    minecraftUuid: doc.minecraftUuid,
    status: doc.status,
    linkedAt: doc.linkedAt,
    unlinkedAt: doc.unlinkedAt,
    linkedBy: doc.linkedBy,
  }
}

function toSessionRecord(doc: PlayerSession): SessionRecord {
  return {
    minecraftUuid: doc.minecraftUuid,
    serverId: doc.serverId,
    startedAt: doc.startedAt,
    endedAt: doc.endedAt,
    status: doc.status,
  }
}

export class MongoUserRepository implements UserRepository {
  async findByUuid(uuid: string): Promise<UserRecord | null> {
    const doc = await UserModel.findOne({ "minecraft.uuid": uuid }).lean()
    return doc ? toUserRecord(doc) : null
  }

  async findByTelegramId(telegramId: string): Promise<UserRecord | null> {
    if (telegramId === "") return null
    const doc = await UserModel.findOne({ "telegram.id": telegramId }).lean()
    return doc ? toUserRecord(doc) : null
  }

  async findByUsername(username: string): Promise<UserRecord | null> {
    const doc = await UserModel.findOne({ "minecraft.username": username }).lean()
    return doc ? toUserRecord(doc) : null
  }

  async create(user: UserRecord): Promise<void> {
    try {
      await UserModel.create({
        minecraft: { uuid: user.minecraftUuid, username: user.username },
        telegram: { id: user.telegramId, username: user.telegramUsername, linkedAt: user.telegramLinkedAt },
        status: user.status,
        language: user.language,
        lastApplicationAt: user.lastApplicationAt,
      })
    } catch (err: unknown) {
      throw mapDuplicate(err, "user")
    }
  }

  async update(user: UserRecord): Promise<void> {
    await UserModel.updateOne(
      { "minecraft.uuid": user.minecraftUuid },
      {
        $set: {
          "telegram.id": user.telegramId,
          "telegram.username": user.telegramUsername,
          "telegram.linkedAt": user.telegramLinkedAt,
          status: user.status,
          language: user.language,
          lastApplicationAt: user.lastApplicationAt,
        },
      },
    )
  }
}

export class MongoTelegramLinkRepository implements TelegramLinkRepository {
  async findByTelegramId(telegramId: string): Promise<LinkRecord | null> {
    const doc = await TelegramLinkModel.findOne({ telegramId }).lean()
    return doc ? toLinkRecord(doc) : null
  }

  async findByUuid(uuid: string): Promise<LinkRecord | null> {
    const doc = await TelegramLinkModel.findOne({ minecraftUuid: uuid }).lean()
    return doc ? toLinkRecord(doc) : null
  }

  async create(link: LinkRecord): Promise<void> {
    try {
      await TelegramLinkModel.create(link)
    } catch (err: unknown) {
      throw mapDuplicate(err, "telegram_link")
    }
  }

  async update(link: LinkRecord): Promise<void> {
    await TelegramLinkModel.updateOne(
      { telegramId: link.telegramId },
      {
        $set: {
          minecraftUuid: link.minecraftUuid,
          status: link.status,
          linkedAt: link.linkedAt,
          unlinkedAt: link.unlinkedAt,
          linkedBy: link.linkedBy,
        },
      },
    )
  }

  async deleteByTelegramId(telegramId: string): Promise<void> {
    await TelegramLinkModel.deleteOne({ telegramId })
  }
}

export class MongoSessionRepository implements SessionRepository {
  async findActiveByUuid(uuid: string): Promise<SessionRecord | null> {
    const doc = await PlayerSessionModel.findOne({ minecraftUuid: uuid, endedAt: null }).lean()
    return doc ? toSessionRecord(doc) : null
  }

  async create(session: SessionRecord): Promise<void> {
    await PlayerSessionModel.create(session)
  }

  async update(session: SessionRecord): Promise<void> {
    await PlayerSessionModel.updateOne(
      { minecraftUuid: session.minecraftUuid, endedAt: null },
      {
        $set: {
          serverId: session.serverId,
          status: session.status,
          endedAt: session.endedAt,
        },
      },
    )
  }
}

export class MongoAuthRequestRepository implements AuthRequestRepository {
  async cancelPendingForUuid(uuid: string): Promise<number> {
    const result = await AuthRequestModel.updateMany(
      { minecraftUuid: uuid, status: "PENDING" },
      { $set: { status: "CANCELLED" } },
    )
    return result.modifiedCount
  }
}

export class MongoAuditRepository implements AuditRepository {
  async append(record: AuditRecord): Promise<void> {
    await AuditLogModel.create({
      actorType: record.actorType,
      actorId: record.actorId,
      action: record.action,
      targetType: record.targetType,
      targetId: record.targetId,
      details: record.details,
      serverId: record.serverId,
      relatedSessionId: record.relatedSessionId,
    })
  }
}
