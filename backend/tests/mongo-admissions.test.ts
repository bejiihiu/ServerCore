import { afterAll, beforeAll, describe, expect, test } from "bun:test"
import mongoose from "mongoose"
import {
  ApplicationDraftModel,
  ApplicationModel,
  VoteModel,
} from "../src/database/models/index.js"
import {
  MongooseApplicationDraftRepository,
  MongooseApplicationRepository,
  MongooseVoteRepository,
} from "../src/database/repos.js"
import { expectDuplicateKey, mongoUnavailableReason, startTestMongo, type TestMongo } from "./helpers/mongo.js"
import type { NewApplication } from "../src/services/ports.js"

/**
 * admission invariants that live in the database, not in the service layer:
 * one live application per player (partial unique on the live statuses), one
 * vote per voter per application (compound unique), and the durable draft
 * upsert keyed by telegram account.
 */

const mongo: TestMongo | null = await startTestMongo("admissions")
if (!mongo) console.warn(`[mongo admissions skipped] ${mongoUnavailableReason()}`)

const describeDb = describe.skipIf(mongo === null)

afterAll(async () => {
  await mongo?.stop()
})

const apps = new MongooseApplicationRepository()
const votes = new MongooseVoteRepository()
const drafts = new MongooseApplicationDraftRepository()

const NOW = new Date("2026-11-25T09:00:00Z")

function newApplication(uuid: string): NewApplication {
  return {
    minecraftUuid: uuid,
    telegramId: `tg-${uuid}`,
    answers: [{ question: "q1", answer: "a1" }],
    voting: {
      startedAt: NOW,
      expiresAt: new Date(NOW.getTime() + 3 * 3_600_000),
      eligibleVoters: 5,
    },
  }
}

describeDb("applications: one live application per player", () => {
  beforeAll(async () => {
    await Promise.all([ApplicationModel.syncIndexes(), VoteModel.syncIndexes(), ApplicationDraftModel.syncIndexes()])
  })

  test("two simultaneous submits for the same uuid: exactly one live application", async () => {
    const results = await Promise.all([apps.insert(newApplication("uuid-race")), apps.insert(newApplication("uuid-race"))])

    const inserted = results.filter((result) => result !== "duplicate-live")
    const rejected = results.filter((result) => result === "duplicate-live")

    expect(inserted).toHaveLength(1)
    expect(rejected).toHaveLength(1)
    expect(await ApplicationModel.countDocuments({ minecraftUuid: "uuid-race" })).toBe(1)
    expect(await apps.findLiveByUuid("uuid-race")).not.toBeNull()
  })

  test("the index itself rejects a second live row, even bypassing the repository", async () => {
    const first = await apps.insert(newApplication("uuid-direct"))
    expect(first === "duplicate-live").toBe(false)

    const code = await expectDuplicateKey(
      ApplicationModel.collection.insertOne({
        minecraftUuid: "uuid-direct",
        telegramId: "tg-direct",
        answers: [],
        status: "VOTING",
        voting: {
          startedAt: NOW,
          expiresAt: NOW,
          eligibleVoters: 5,
          votesFor: 0,
          votesAgainst: 0,
          abstentions: 0,
          extensionCount: 0,
          votesCast: null,
          quorumReached: null,
          decidedAt: null,
        },
      }),
    )

    expect(code).toBe(11000)
  })

  test("a decided application frees the slot for a new submit", async () => {
    const first = await apps.insert(newApplication("uuid-reapply"))
    if (first === "duplicate-live") throw new Error("seed failed")

    expect(await apps.setStatus(first.id, ["VOTING"], "REJECTED")).toBe(true)

    const second = await apps.insert(newApplication("uuid-reapply"))

    expect(second === "duplicate-live").toBe(false)
    expect(await ApplicationModel.countDocuments({ minecraftUuid: "uuid-reapply" })).toBe(2)
    expect((await apps.findLiveByUuid("uuid-reapply"))?.id).toBe(second === "duplicate-live" ? "" : second.id)
  })

  test("different players can submit at the same time", async () => {
    const results = await Promise.all([
      apps.insert(newApplication("uuid-parallel-1")),
      apps.insert(newApplication("uuid-parallel-2")),
    ])

    expect(results.every((result) => result !== "duplicate-live")).toBe(true)
  })
})

describeDb("votes: compound unique (applicationId, voterUuid)", () => {
  let applicationId = ""

  beforeAll(async () => {
    const app = await ApplicationModel.create({
      minecraftUuid: "uuid-voting-target",
      telegramId: "tg-target",
      answers: [],
      status: "VOTING",
      voting: {
        startedAt: NOW,
        expiresAt: NOW,
        eligibleVoters: 5,
        votesFor: 0,
        votesAgainst: 0,
        abstentions: 0,
        extensionCount: 0,
        votesCast: null,
        quorumReached: null,
        decidedAt: null,
      },
    })
    applicationId = app._id.toString()
  })

  test("a second vote by the same player on the same application is rejected", async () => {
    const first = await votes.insert({ applicationId, voterUuid: "voter-1", decision: "ACCEPT", createdAt: NOW })
    const second = await votes.insert({ applicationId, voterUuid: "voter-1", decision: "REJECT", createdAt: NOW })

    expect(first).toBe("inserted")
    expect(second).toBe("duplicate")
    expect(await VoteModel.countDocuments({ applicationId, voterUuid: "voter-1" })).toBe(1)
  })

  test("two simultaneous clicks: one insert wins, the other is a duplicate", async () => {
    const results = await Promise.all([
      votes.insert({ applicationId, voterUuid: "voter-race", decision: "ACCEPT", createdAt: NOW }),
      votes.insert({ applicationId, voterUuid: "voter-race", decision: "ACCEPT", createdAt: NOW }),
    ])

    expect(results.filter((result) => result === "inserted")).toHaveLength(1)
    expect(results.filter((result) => result === "duplicate")).toHaveLength(1)
    expect(await VoteModel.countDocuments({ applicationId, voterUuid: "voter-race" })).toBe(1)
  })

  test("the index rejects a duplicate even when the pre-check is bypassed", async () => {
    const code = await expectDuplicateKey(
      VoteModel.collection.insertOne({
        applicationId: new mongoose.Types.ObjectId(applicationId),
        voterUuid: "voter-1",
        decision: "ACCEPT",
      }),
    )

    expect(code).toBe(11000)
  })

  test("an alt telegram account cannot double vote: the voter identity is the uuid", async () => {
    // a dedicated application keeps the tally independent from the other cases
    const target = await ApplicationModel.create({
      minecraftUuid: "uuid-voting-alt",
      telegramId: "tg-alt-target",
      answers: [],
      status: "VOTING",
      voting: {
        startedAt: NOW,
        expiresAt: NOW,
        eligibleVoters: 5,
        votesFor: 0,
        votesAgainst: 0,
        abstentions: 0,
        extensionCount: 0,
        votesCast: null,
        quorumReached: null,
        decidedAt: null,
      },
    })
    const altApplicationId = target._id.toString()

    // two telegram accounts owned by the same player still share one minecraft uuid
    const fromMain = await votes.insert({
      applicationId: altApplicationId,
      voterUuid: "voter-alt",
      decision: "ACCEPT",
      createdAt: NOW,
    })
    const fromAlt = await votes.insert({
      applicationId: altApplicationId,
      voterUuid: "voter-alt",
      decision: "REJECT",
      createdAt: NOW,
    })

    expect(fromMain).toBe("inserted")
    expect(fromAlt).toBe("duplicate")
    const tally = await votes.countByDecision(altApplicationId)
    expect(tally).toEqual({ votesFor: 1, votesAgainst: 0 })
  })

  test("the same voter may vote on different applications", async () => {
    const other = await ApplicationModel.create({
      minecraftUuid: "uuid-voting-other",
      telegramId: "tg-other",
      answers: [],
      status: "VOTING",
      voting: {
        startedAt: NOW,
        expiresAt: NOW,
        eligibleVoters: 5,
        votesFor: 0,
        votesAgainst: 0,
        abstentions: 0,
        extensionCount: 0,
        votesCast: null,
        quorumReached: null,
        decidedAt: null,
      },
    })

    const inserted = await votes.insert({
      applicationId: other._id.toString(),
      voterUuid: "voter-1",
      decision: "ACCEPT",
      createdAt: NOW,
    })

    expect(inserted).toBe("inserted")
  })
})

describeDb("application drafts: durable upsert and ttl", () => {
  test("saving twice keeps a single draft per telegram account and the latest answers", async () => {
    const base = {
      telegramId: "700000001",
      minecraftUuid: "uuid-draft-1",
      username: "drafter",
      language: "ru" as const,
    }

    await drafts.save({ ...base, step: 0, answers: [] })
    await drafts.save({ ...base, step: 1, answers: ["first"] })

    expect(await ApplicationDraftModel.countDocuments({ telegramId: base.telegramId })).toBe(1)
    const stored = await drafts.findByTelegramId(base.telegramId)
    expect(stored?.step).toBe(1)
    expect(stored?.answers).toEqual(["first"])
  })

  test("different telegram accounts hold independent drafts", async () => {
    const base = { minecraftUuid: "uuid-draft-shared", username: "same-player", step: 0, answers: [], language: "ru" as const }
    await drafts.save({ ...base, telegramId: "700000002" })
    await drafts.save({ ...base, telegramId: "700000003" })

    expect(await ApplicationDraftModel.countDocuments({ minecraftUuid: "uuid-draft-shared" })).toBe(2)
  })

  test("abandoned drafts expire through the ttl index", async () => {
    const indexes = (await ApplicationDraftModel.collection.indexes()) as unknown as {
      key: Record<string, unknown>
      expireAfterSeconds?: number
    }[]
    const ttl = indexes.find((index) => JSON.stringify(index.key) === JSON.stringify({ updatedAt: 1 }))

    expect(ttl).toBeDefined()
    expect(ttl?.expireAfterSeconds).toBe(30 * 86_400)
  })
})
