import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import mongoose from "mongoose"
import { TelegramLinkModel, UserModel } from "../src/database/models/index.js"
import { MongoTelegramLinkRepository } from "../src/modules/account/repository-mongo.js"
import { DuplicateKeyError } from "../src/modules/account/repository.js"
import { expectDuplicateKey, mongoUnavailableReason, startTestMongo, type TestMongo } from "./helpers/mongo.js"

/**
 * the users collection carries two hard invariants the in-memory twin only
 * pretends to hold: one account per minecraft uuid, and one account per linked
 * telegram id while unlinked accounts (empty/absent telegram id) must not
 * collide with each other.
 */

const mongo: TestMongo | null = await startTestMongo("users")
if (!mongo) console.warn(`[mongo users skipped] ${mongoUnavailableReason()}`)

const describeDb = describe.skipIf(mongo === null)

afterAll(async () => {
  await mongo?.stop()
})

type NewUser = {
  uuid: string
  username?: string
  telegramId?: string | null
}

/** creates through the model so schema defaults apply, like production does */
async function createUser(input: NewUser): Promise<mongoose.HydratedDocument<unknown>> {
  return UserModel.create({
    minecraft: { uuid: input.uuid, username: input.username ?? `player-${input.uuid}` },
    ...(input.telegramId === null ? {} : { telegram: { id: input.telegramId ?? "" } }),
    status: "UNREGISTERED",
  })
}

describeDb("users indexes on a real mongod", () => {
  beforeAll(async () => {
    await UserModel.syncIndexes()
  })

  test("two accounts with the same minecraft uuid: the second write is rejected", async () => {
    const uuid = "uuid-unique-1"
    await createUser({ uuid })

    const code = await expectDuplicateKey(createUser({ uuid, username: "other-name" }))

    expect(code).toBe(11000)
    expect(await UserModel.countDocuments({ "minecraft.uuid": uuid })).toBe(1)
  })

  test("two accounts with the same linked telegram id: the second write is rejected", async () => {
    await createUser({ uuid: "uuid-tg-a", telegramId: "900000001" })

    const code = await expectDuplicateKey(createUser({ uuid: "uuid-tg-b", telegramId: "900000001" }))

    expect(code).toBe(11000)
    expect(await UserModel.countDocuments({ "telegram.id": "900000001" })).toBe(1)
  })

  test("unlinked accounts (empty telegram id) never collide with each other", async () => {
    const uuids = ["uuid-empty-1", "uuid-empty-2", "uuid-empty-3"]
    const created = await Promise.all(uuids.map((uuid) => createUser({ uuid })))

    expect(created).toHaveLength(3)
    expect(await UserModel.countDocuments({ "telegram.id": "", "minecraft.uuid": { $in: uuids } })).toBe(3)
  })

  test("a document with no telegram.id field at all is outside the partial index", async () => {
    // raw insert: the schema default would otherwise fill in ""
    await mongoose.connection.collection("users").insertOne({
      minecraft: { uuid: "uuid-absent-1", username: "absent-1" },
      status: "UNREGISTERED",
      language: "ru",
    })
    await mongoose.connection.collection("users").insertOne({
      minecraft: { uuid: "uuid-absent-2", username: "absent-2" },
      status: "UNREGISTERED",
      language: "ru",
    })

    expect(await UserModel.countDocuments({ "telegram.id": { $exists: false } })).toBe(2)
  })

  test("a null telegram id is treated like an unlinked account", async () => {
    await createUser({ uuid: "uuid-null-1", telegramId: null })
    await createUser({ uuid: "uuid-null-2", telegramId: null })

    // the path normalizes null to "" (the schema default) — either way it must
    // stay outside the partial index, so two such accounts coexist
    const docs = await UserModel.find(
      { "minecraft.uuid": { $in: ["uuid-null-1", "uuid-null-2"] } },
      { "telegram.id": 1 },
    ).lean()

    expect(docs).toHaveLength(2)
    expect(docs.every((doc) => doc.telegram?.id === "" || doc.telegram?.id === null)).toBe(true)
  })

  test("link-after-registration hits the index: an empty slot can be claimed once", async () => {
    const first = await createUser({ uuid: "uuid-claim-1" })
    const second = await createUser({ uuid: "uuid-claim-2" })

    await UserModel.updateOne({ _id: first._id }, { $set: { "telegram.id": "900000002" } })
    const code = await expectDuplicateKey(
      UserModel.updateOne({ _id: second._id }, { $set: { "telegram.id": "900000002" } }),
    )

    expect(code).toBe(11000)
    expect(await UserModel.countDocuments({ "telegram.id": "900000002" })).toBe(1)
  })
})

describeDb("telegram links: one telegram account, one minecraft account", () => {
  const links = new MongoTelegramLinkRepository()

  beforeAll(async () => {
    await TelegramLinkModel.syncIndexes()
  })

  const link = (telegramId: string, minecraftUuid: string) => ({
    telegramId,
    minecraftUuid,
    status: "LINKED" as const,
    linkedAt: new Date("2026-11-25T09:00:00Z"),
    unlinkedAt: null,
    linkedBy: "bot",
  })

  test("a telegram account cannot be linked to a second minecraft account", async () => {
    await links.create(link("910000001", "uuid-link-a"))

    let mapped: unknown = null
    try {
      await links.create(link("910000001", "uuid-link-b"))
    } catch (err: unknown) {
      mapped = err
    }

    expect(mapped).toBeInstanceOf(DuplicateKeyError)
    expect(await TelegramLinkModel.countDocuments({ telegramId: "910000001" })).toBe(1)
  })

  test("a minecraft account cannot be linked to a second telegram account", async () => {
    await links.create(link("910000002", "uuid-link-c"))

    let mapped: unknown = null
    try {
      await links.create(link("910000003", "uuid-link-c"))
    } catch (err: unknown) {
      mapped = err
    }

    expect(mapped).toBeInstanceOf(DuplicateKeyError)
    expect(await TelegramLinkModel.countDocuments({ minecraftUuid: "uuid-link-c" })).toBe(1)
  })
})
