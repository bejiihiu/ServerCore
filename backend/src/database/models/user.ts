import { model, Schema } from "mongoose"

export const USER_STATUSES = [
  "UNREGISTERED",
  "APPLICATION_PENDING",
  "VOTING",
  "APPROVED",
  "ACTIVE",
  "SUSPENDED",
  "BANNED",
  "DELETION_PENDING",
] as const

export type UserStatus = (typeof USER_STATUSES)[number]

export type UserLanguage = "ru" | "en"

export interface User {
  minecraft: {
    uuid: string
    username: string
  }
  telegram: {
    id: string
    username: string | null
    linkedAt: Date | null
    /** set when the user joined the closed group through their personal invite */
    groupLinkedAt: Date | null
  }
  status: UserStatus
  /** bot language preference, interface language for personal messages */
  language: UserLanguage
  lastApplicationAt: Date | null
  createdAt: Date
  updatedAt: Date
}

const userSchema = new Schema<User>(
  {
    minecraft: {
      uuid: { type: String, required: true },
      username: { type: String, required: true },
    },
    telegram: {
      // empty string until the account is actually linked; the partial unique
      // index below ignores empties so unregistered users don't collide
      id: { type: String, default: "" },
      username: { type: String, default: null },
      linkedAt: { type: Date, default: null },
      groupLinkedAt: { type: Date, default: null },
    },
    status: { type: String, enum: USER_STATUSES, required: true },
    language: { type: String, enum: ["ru", "en"], required: true, default: "ru" },
    lastApplicationAt: { type: Date, default: null },
  },
  { timestamps: true },
)

userSchema.index({ "minecraft.uuid": 1 }, { unique: true })
userSchema.index({ "minecraft.username": 1 })
userSchema.index(
  { "telegram.id": 1 },
  { unique: true, partialFilterExpression: { "telegram.id": { $gt: "" } } },
)

export const UserModel = model<User>("User", userSchema, "users")
