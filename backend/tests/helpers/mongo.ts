import mongoose from "mongoose"
import { MongoMemoryServer } from "mongodb-memory-server"

/**
 * real-mongod harness for the repository tests. the in-memory twins can't prove
 * unique/partial/ttl index behaviour, so these suites run against an actual
 * server: mongodb-memory-server by default, or MONGODB_TEST_URI when a local
 * mongod is provided (the download may be blocked in some environments).
 *
 * one mongod per test file, one database per file, dropped before and after the
 * run. bun runs test files sequentially in a single process, so each file gets
 * the global mongoose connection to itself.
 */

// indexes are created explicitly in tests (that's half of what we assert here),
// mirroring the production boot which flips autoIndex off before connecting
mongoose.set("autoIndex", false)
mongoose.set("strictQuery", true)

let unavailable: string | null = null

/** why the last startTestMongo() call returned null — for the skip messages */
export function mongoUnavailableReason(): string {
  return unavailable ?? "mongod not available"
}

export type TestMongo = {
  dbName: string
  uri: string
  stop(): Promise<void>
}

function databaseName(suite: string): string {
  const safe = suite
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, "_")
    .replace(/^_+|_+$/g, "")
  return `servercore_test_${safe}_${process.pid}`
}

function withDatabase(uri: string, dbName: string): string {
  const url = new URL(uri)
  url.pathname = `/${dbName}`
  return url.toString()
}

/**
 * connects mongoose to a throwaway database for one suite. returns null when no
 * mongod can be reached; callers mark their suite skipped in that case.
 */
export async function startTestMongo(suite: string): Promise<TestMongo | null> {
  const dbName = databaseName(suite)
  const externalUri = process.env.MONGODB_TEST_URI?.trim()
  let memoryServer: MongoMemoryServer | null = null

  try {
    let uri: string
    if (externalUri) {
      uri = withDatabase(externalUri, dbName)
    } else {
      memoryServer = await MongoMemoryServer.create({ instance: { dbName } })
      uri = memoryServer.getUri(dbName)
    }

    await mongoose.connect(uri, { serverSelectionTimeoutMS: 5_000 })
    await mongoose.connection.dropDatabase()

    return {
      dbName,
      uri,
      async stop(): Promise<void> {
        if (mongoose.connection.readyState === 1) {
          await mongoose.connection.dropDatabase()
        }
        await mongoose.disconnect()
        if (memoryServer) await memoryServer.stop()
        memoryServer = null
      },
    }
  } catch (err: unknown) {
    // a half-open connection would poison the next suite
    await mongoose.disconnect().catch(() => undefined)
    if (memoryServer) await memoryServer.stop().catch(() => undefined)
    const cause = err instanceof Error ? err.message : String(err)
    unavailable = externalUri
      ? `could not connect to MONGODB_TEST_URI: ${cause}`
      : `mongodb-memory-server could not start a mongod (no MONGODB_TEST_URI set): ${cause}`
    return null
  }
}

/** duplicate-key predicate that works for driver and mongoose errors */
export function isDuplicateKeyError(err: unknown): boolean {
  return typeof err === "object" && err !== null && (err as { code?: unknown }).code === 11000
}

/** awaits a promise expected to fail with e11000, returns the code */
export async function expectDuplicateKey(write: Promise<unknown>): Promise<number> {
  try {
    await write
  } catch (err: unknown) {
    const code = (err as { code?: unknown } | null)?.code
    return typeof code === "number" ? code : -1
  }
  return 0
}
