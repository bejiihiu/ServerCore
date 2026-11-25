import { afterAll, describe, expect, test } from "bun:test"
import mongoose from "mongoose"
import pino from "pino"
import { syncIndexesWithTolerance } from "../src/database/connection.js"
import "../src/database/models/index.js"
import { mongoUnavailableReason, startTestMongo, type TestMongo } from "./helpers/mongo.js"

/**
 * proves the boot-time index sync actually materializes every index the schemas
 * declare. this is the only place the whole model set is checked against a real
 * server: a model dropped from syncIndexesWithTolerance would silently lose its
 * unique constraints in production and nothing else would notice.
 */

const logger = pino({ level: "silent" })

const mongo: TestMongo | null = await startTestMongo("index-sync")
if (!mongo) console.warn(`[mongo index sync skipped] ${mongoUnavailableReason()}`)

const describeDb = describe.skipIf(mongo === null)

type CreatedIndex = {
  name: string
  key: Record<string, unknown>
  unique?: boolean
  expireAfterSeconds?: number
  partialFilterExpression?: Record<string, unknown>
}

function keySignature(key: Record<string, unknown>): string {
  return JSON.stringify(Object.entries(key))
}

async function createdIndexes(model: mongoose.Model<unknown>): Promise<CreatedIndex[]> {
  return (await model.collection.indexes()) as unknown as CreatedIndex[]
}

describeDb("index sync against a real mongod", () => {
  afterAll(async () => {
    await mongo?.stop()
  })

  test("syncIndexesWithTolerance creates every declared index of every model", async () => {
    await syncIndexesWithTolerance(logger)

    const models = Object.values(mongoose.models)
    expect(models.length).toBeGreaterThanOrEqual(14)

    const problems: string[] = []
    for (const model of models) {
      const declared = model.schema.indexes()
      if (declared.length === 0) problems.push(`${model.modelName}: no indexes declared`)

      const byKey = new Map((await createdIndexes(model)).map((index) => [keySignature(index.key), index]))

      for (const [keys, options] of declared) {
        const signature = keySignature(keys as Record<string, unknown>)
        const found = byKey.get(signature)
        const label = `${model.modelName} ${String(options?.name ?? signature)}`
        if (!found) {
          problems.push(`${label}: missing on the server`)
          continue
        }
        if (options?.unique === true && found.unique !== true) problems.push(`${label}: not unique`)
        if (options?.expireAfterSeconds !== undefined && found.expireAfterSeconds !== options.expireAfterSeconds) {
          problems.push(`${label}: ttl ${String(found.expireAfterSeconds)} != ${options.expireAfterSeconds}`)
        }
        if (options?.partialFilterExpression !== undefined) {
          const expected = JSON.stringify(options.partialFilterExpression)
          const actual = JSON.stringify(found.partialFilterExpression ?? null)
          if (expected !== actual) problems.push(`${label}: partial filter ${actual} != ${expected}`)
        }
      }
    }

    expect(problems).toEqual([])
  })

  test("a second sync is idempotent: no index churn on restart", async () => {
    const before = new Map<string, string[]>()
    for (const model of Object.values(mongoose.models)) {
      before.set(
        model.modelName,
        (await createdIndexes(model)).map((index) => index.name).sort(),
      )
    }

    await syncIndexesWithTolerance(logger)

    for (const model of Object.values(mongoose.models)) {
      expect((await createdIndexes(model)).map((index) => index.name).sort()).toEqual(
        before.get(model.modelName) ?? [],
      )
    }
  })

  test("the ttl indexes carry the lifetimes the models declare", async () => {
    // guards the numbers themselves: a typo here would make expired rows linger
    const expectedTtls: { collection: string; field: string; seconds: number }[] = [
      { collection: "auth_requests", field: "expiresAt", seconds: 0 },
      { collection: "application_drafts", field: "updatedAt", seconds: 30 * 86_400 },
      { collection: "conversation_states", field: "updatedAt", seconds: 30 * 86_400 },
    ]

    for (const expected of expectedTtls) {
      const indexes = (await mongoose.connection.collection(expected.collection).indexes()) as unknown as CreatedIndex[]
      const index = indexes.find((candidate) => keySignature(candidate.key) === keySignature({ [expected.field]: 1 }))
      expect(index).toBeDefined()
      expect(index?.expireAfterSeconds).toBe(expected.seconds)
    }
  })

  test("synced indexes are enforced, not just listed", async () => {
    const events = mongoose.connection.collection("server_events")
    await events.insertOne({
      messageId: "wire-1",
      serverId: "s1",
      type: "PING",
      payload: {},
      status: "RECEIVED",
      receivedAt: new Date(),
    })

    let code = 0
    try {
      await events.insertOne({
        messageId: "wire-1",
        serverId: "s1",
        type: "PING",
        payload: {},
        status: "RECEIVED",
        receivedAt: new Date(),
      })
    } catch (err: unknown) {
      code = (err as { code?: number }).code ?? 0
    }
    expect(code).toBe(11000)
  })
})
