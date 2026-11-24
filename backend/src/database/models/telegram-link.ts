import { model, Schema } from "mongoose"

export const TELEGRAM_LINK_STATUSES = ["PENDING", "LINKED", "UNLINKED"] as const

export type TelegramLinkStatus = (typeof TELEGRAM_LINK_STATUSES)[number]

/**
 * join records between a telegram account and a minecraft account.
 * the users collection holds the authoritative snapshot; this collection is
 * the link ledger (history of links/unlinks with proof of ownership).
 */
export interface TelegramLink {
  telegramId: string
  minecraftUuid: string
  status: TelegramLinkStatus
  linkedAt: Date | null
  unlinkedAt: Date | null
  linkedBy: string | null
}

const telegramLinkSchema = new Schema<TelegramLink>(
  {
    telegramId: { type: String, required: true },
    minecraftUuid: { type: String, required: true },
    status: { type: String, enum: TELEGRAM_LINK_STATUSES, required: true },
    linkedAt: { type: Date, default: null },
    unlinkedAt: { type: Date, default: null },
    // who confirmed the link: bot flow, admin, or server-side verification
    linkedBy: { type: String, default: null },
  },
  { timestamps: true },
)

// a telegram account can only ever be linked to one minecraft account
telegramLinkSchema.index({ telegramId: 1 }, { unique: true })
telegramLinkSchema.index({ minecraftUuid: 1 }, { unique: true })
telegramLinkSchema.index({ status: 1 })

export const TelegramLinkModel = model<TelegramLink>("TelegramLink", telegramLinkSchema, "telegram_links")
