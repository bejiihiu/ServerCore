import { describe, expect, test } from "bun:test"
import { decideOutcome, hasQuorum, isApproved, shouldAutoExtend } from "../src/services/voting-math.js"

describe("quorum math", () => {
  test("exactly 50% voted is NOT a quorum (strictly more than half required)", () => {
    expect(hasQuorum(10, 20)).toBe(false)
    expect(hasQuorum(1, 2)).toBe(false)
    expect(hasQuorum(50, 100)).toBe(false)
  })

  test("more than 50% voted is a quorum", () => {
    expect(hasQuorum(11, 20)).toBe(true)
    expect(hasQuorum(2, 3)).toBe(true)
    expect(hasQuorum(1, 1)).toBe(true)
  })

  test("zero votes never reach quorum, even with a tiny pool", () => {
    expect(hasQuorum(0, 0)).toBe(false)
    expect(hasQuorum(0, 1)).toBe(false)
  })

  test("single eligible voter must vote themselves", () => {
    expect(hasQuorum(1, 1)).toBe(true)
    expect(hasQuorum(0, 1)).toBe(false)
  })
})

describe("result math", () => {
  test("exactly 50% in favour is a rejection (ties reject)", () => {
    expect(isApproved(2, 2)).toBe(false)
    expect(isApproved(10, 10)).toBe(false)
  })

  test("strict majority approves", () => {
    expect(isApproved(3, 2)).toBe(true)
    expect(isApproved(8, 4)).toBe(true)
  })

  test("spec example: 8 for, 4 against approves", () => {
    expect(isApproved(8, 4)).toBe(true)
  })
})

describe("outcome decision", () => {
  test("spec example: 12 of 20 voted, 8 for -> APPROVED", () => {
    expect(decideOutcome(8, 4, 20)).toBe("APPROVED")
  })

  test("quorum shortfall is EXPIRED, never auto-approved", () => {
    expect(decideOutcome(3, 0, 20)).toBe("EXPIRED")
    expect(decideOutcome(0, 0, 20)).toBe("EXPIRED")
  })

  test("quorum reached but majority against -> REJECTED", () => {
    expect(decideOutcome(2, 3, 5)).toBe("REJECTED")
  })

  test("tie with quorum -> REJECTED", () => {
    expect(decideOutcome(3, 3, 6)).toBe("REJECTED")
  })

  test("unanimous approval", () => {
    expect(decideOutcome(5, 0, 5)).toBe("APPROVED")
  })
})

describe("auto-extension rule", () => {
  test("first shortfall extends", () => {
    expect(shouldAutoExtend(4, 20, 0)).toBe(true)
  })

  test("second shortfall does not extend again", () => {
    expect(shouldAutoExtend(4, 20, 1)).toBe(false)
  })

  test("quorum reached means no extension needed", () => {
    expect(shouldAutoExtend(11, 20, 0)).toBe(false)
  })
})
