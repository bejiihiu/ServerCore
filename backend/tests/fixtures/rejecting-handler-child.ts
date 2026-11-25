/**
 * child-process fixture for the unhandled-rejection chaos test. drives a real
 * ServerConnection + HandlerRegistry whose handler rejects (a repository write
 * failing because mongo went away mid-request) and then reports whether the
 * process survived. anything that escapes the handler must land in the hooks
 * below and fail the test loudly, not silently.
 */
import { HandlerRegistry, silentLogger } from "../../src/websocket/handlers/registry.js"
import { ServerConnection } from "../../src/websocket/connection.js"
import { WS_NOW, connectionOptions, fakeSocket, sentFrames, signedFrame, silentPino } from "../helpers/ws-harness.js"

const boom = (): Error => new Error("MongoServerSelectionError: connection refused")

process.on("unhandledRejection", (reason) => {
  console.log("UNHANDLED_REJECTION", reason instanceof Error ? reason.message : String(reason))
  process.exit(3)
})
process.on("uncaughtException", (error) => {
  console.log("UNCAUGHT_EXCEPTION", error.message)
  process.exit(3)
})

const registry = new HandlerRegistry(silentLogger())
registry.register("SPEC_STARTED", async () => {
  throw boom()
})
let heartbeats = 0
registry.register("SERVER_HEARTBEAT", () => {
  heartbeats += 1
})

const fake = fakeSocket()
const connection = new ServerConnection("game-1", fake.socket, connectionOptions(), silentPino())
const route = (envelope: Parameters<typeof registry.dispatch>[0], conn: ServerConnection) =>
  registry.dispatch(envelope, conn)

connection.handleFrame(
  signedFrame(
    "SPEC_STARTED",
    {
      adminUuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      targetUuid: "1f4c9a3e-0b2d-4c5a-9e8f-7d6c5b4a3928",
      adminUsername: "admin",
      targetUsername: "target",
    },
    "frame-0001",
  ),
  WS_NOW,
  route,
)
// the heartbeat proves the socket still routes frames after the failure
connection.handleFrame(signedFrame("SERVER_HEARTBEAT", { onlinePlayers: 1, maxPlayers: 20 }, "hb-0000001"), WS_NOW, route)

await Bun.sleep(100)

const frames = sentFrames(fake.sent)
console.log(
  "ALIVE",
  `acks=${frames.filter((frame) => frame.type === "ACK").length}`,
  `errors=${frames.filter((frame) => frame.type === "ERROR").length}`,
  `heartbeats=${heartbeats}`,
  `closed=${fake.isClosed()}`,
)
process.exit(0)
