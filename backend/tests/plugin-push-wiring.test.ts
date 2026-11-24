import { describe, expect, test } from "bun:test"
import { applicationDecisionMessageType } from "../src/services/ports.js"
import { payloadSchemas } from "../src/websocket/protocol/message-types.js"

/**
 * the wire mapping between admission outcomes and APPLICATION_* frames.
 * regression: EXPIRED used to travel as APPLICATION_REJECTED, so the plugin's
 * placeholder cache showed "rejected" for quorum-shortfall applications.
 */
describe("admission verdict -> wire message mapping", () => {
  test("each outcome maps to its own message type", () => {
    expect(applicationDecisionMessageType("APPROVED")).toBe("APPLICATION_APPROVED")
    expect(applicationDecisionMessageType("REJECTED")).toBe("APPLICATION_REJECTED")
    expect(applicationDecisionMessageType("EXPIRED")).toBe("APPLICATION_EXPIRED")
  })

  test("every mapped type accepts the decided payload shape", () => {
    for (const type of [
      applicationDecisionMessageType("APPROVED"),
      applicationDecisionMessageType("REJECTED"),
      applicationDecisionMessageType("EXPIRED"),
    ]) {
      const result = payloadSchemas[type].safeParse({
        applicationId: "507f1f77bcf86cd799439011",
        minecraftUuid: "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      })
      expect(result.success).toBe(true)
    }
  })
})
