import { describe, expect, test } from "bun:test"
import {
  FakeTelegramApi,
  FixedClock,
  InMemoryApplicationRepository,
  InMemoryDraftRepository,
  InMemoryGroupInviteRepository,
  InMemoryUserRepository,
  InMemoryVoteRepository,
  InMemoryVotingMessageRepository,
  RecordingAuditSink,
  seedVotingApplication,
  user,
} from "./helpers/in-memory.js"
import { ApplicationService } from "../src/services/application-service.js"
import { VotingService } from "../src/services/voting-service.js"
import { GroupService } from "../src/services/group-service.js"
import { VotingScheduler } from "../src/services/voting-scheduler.js"
import { RateLimiter } from "../src/services/rate-limiter.js"
import { QUESTIONNAIRE_DEFAULTS_MUTABLE } from "./helpers/config.js"

const HOUR = 3_600_000
const THREE_MONTHS = 90 * 24 * HOUR
const VOTING_MS = 3 * HOUR

/** wires the whole admission stack over in-memory seams */
function build(opts: { groupChatId?: number | null } = {}) {
  const clock = new FixedClock()
  const users = new InMemoryUserRepository()
  const applications = new InMemoryApplicationRepository()
  const votes = new InMemoryVoteRepository()
  const messages = new InMemoryVotingMessageRepository()
  const invites = new InMemoryGroupInviteRepository()
  const drafts = new InMemoryDraftRepository()
  const audit = new RecordingAuditSink()
  const telegram = new FakeTelegramApi({
    groupChatId: opts.groupChatId === undefined ? -100_500 : opts.groupChatId,
    adminChatId: 777,
  })

  const applications_ = new ApplicationService({
    users,
    applications,
    drafts,
    audit,
    telegram,
    rateLimiter: new RateLimiter(10, 60_000, clock),
    clock,
    cooldownMs: THREE_MONTHS,
    votingDurationMs: VOTING_MS,
    questions: QUESTIONNAIRE_DEFAULTS_MUTABLE,
    defaultLanguage: "ru",
  })

  const voting = new VotingService({
    users,
    applications,
    votes,
    messages,
    audit,
    telegram,
    clock,
    groupChatId: opts.groupChatId === undefined ? -100_500 : opts.groupChatId,
    defaultLanguage: "ru",
    voteButtonIcons: { acceptId: null, rejectId: null },
    approvalEffectId: "effect-1",
  })

  const group = new GroupService({
    users,
    invites,
    telegram,
    audit,
    clock,
    groupChatId: opts.groupChatId === undefined ? -100_500 : opts.groupChatId,
    inviteTtlMs: 48 * HOUR,
    defaultLanguage: "ru",
  })

  const scheduler = new VotingScheduler({
    voting,
    group,
    logger: { info() {}, warn() {}, error() {} } as never,
    intervalMs: 60_000,
  })

  return { clock, users, applications, votes, messages, invites, drafts, audit, telegram, applications_, voting, group, scheduler }
}

/** completes the questionnaire draft for a telegram account */
async function fillDraft(deps: ReturnType<typeof build>, telegramId: string) {
  await deps.applications_.startDraft(telegramId, "ru")
  for (let i = 0; i < QUESTIONNAIRE_DEFAULTS_MUTABLE.ru.length; i++) {
    const saved = await deps.applications_.saveDraftStep(telegramId, i, `answer-${i + 1}`)
    if (!saved.ok) throw new Error("draft step failed")
  }
}

describe("application flow", () => {
  test("unlinked telegram account cannot start a draft", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", telegramId: "" }))
    const outcome = await deps.applications_.startDraft("tg-u1")
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("NO_USER")
  })

  test("banned account cannot apply", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", status: "BANNED" }))
    const outcome = await deps.applications_.startDraft("tg-u1")
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("INELIGIBLE_STATUS")
  })

  test("3-month cooldown blocks a new application, independent of deleted applications", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", status: "UNREGISTERED", lastApplicationAt: new Date(deps.clock.nowMs() - 89 * 24 * HOUR) }))
    const outcome = await deps.applications_.startDraft("tg-u1")
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) {
      expect(outcome.reason).toBe("COOLDOWN")
      expect(outcome.cooldownUntil).toBeDefined()
    }

    // after the cooldown passes the same user (whose application was never
    // even stored) can apply again
    deps.clock.advance(THREE_MONTHS)
    const second = await deps.applications_.startDraft("tg-u1")
    expect(second.ok).toBe(true)
  })

  test("cooldown applies right after a rejected application even if it was deleted", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", status: "UNREGISTERED" }))
    await fillDraft(deps, "tg-u1")
    const submitted = await deps.applications_.submit("tg-u1")
    expect(submitted.ok).toBe(true)
    if (!submitted.ok) return

    // emulate a rejected voting: the user goes back to UNREGISTERED
    await deps.applications.saveDecision(submitted.application.id, {
      status: "REJECTED",
      votesCast: 0,
      quorumReached: false,
      decidedAt: new Date(deps.clock.nowMs()),
    })
    await deps.users.setStatus("u1", ["VOTING"], "UNREGISTERED")
    // then the application document is deleted outright
    deps.applications.delete(submitted.application.id)

    // the cooldown rides on user.lastApplicationAt, not on the document
    const again = await deps.applications_.startDraft("tg-u1")
    expect(again.ok).toBe(false)
    if (!again.ok) expect(again.reason).toBe("COOLDOWN")
  })

  test("double submit is impossible: the draft delete is the single-winner gate", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", status: "UNREGISTERED" }))
    await fillDraft(deps, "tg-u1")
    const first = await deps.applications_.submit("tg-u1")
    expect(first.ok).toBe(true)
    const second = await deps.applications_.submit("tg-u1")
    expect(second.ok).toBe(false)
    if (!second.ok) expect(second.reason).toBe("NO_DRAFT")
  })

  test("partial draft cannot be submitted", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", status: "UNREGISTERED" }))
    await deps.applications_.startDraft("tg-u1", "ru")
    await deps.applications_.saveDraftStep("tg-u1", 0, "only the first answer")
    const outcome = await deps.applications_.submit("tg-u1")
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("INCOMPLETE")
  })

  test("answer validation: empty rejected, whitespace trimmed, overlong rejected", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", status: "UNREGISTERED" }))
    await deps.applications_.startDraft("tg-u1", "ru")

    expect((await deps.applications_.saveDraftStep("tg-u1", 0, "   ")).ok).toBe(false)
    expect((await deps.applications_.saveDraftStep("tg-u1", 0, "a".repeat(1001))).ok).toBe(false)
    expect((await deps.applications_.saveDraftStep("tg-u1", 0, "  spaced answer  ")).ok).toBe(true)
    const draft = await deps.drafts.findByTelegramId("tg-u1")
    expect(draft?.answers[0]).toBe("spaced answer")
  })

  test("questionnaire comes from config: custom question list is honoured", async () => {
    const deps = build()
    expect(deps.applications_.questionCount).toBe(QUESTIONNAIRE_DEFAULTS_MUTABLE.ru.length)
    expect(deps.applications_.questionText("ru", 0)).toBe(QUESTIONNAIRE_DEFAULTS_MUTABLE.ru[0] ?? null)
    expect(deps.applications_.questionText("en", 0)).toBe(QUESTIONNAIRE_DEFAULTS_MUTABLE.en[0] ?? null)
  })

  test("view shows the latest submitted application", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", status: "UNREGISTERED" }))
    const none = await deps.applications_.getSubmittedApplication("tg-u1")
    expect(none.ok).toBe(false)

    await fillDraft(deps, "tg-u1")
    await deps.applications_.submit("tg-u1")
    const view = await deps.applications_.getSubmittedApplication("tg-u1")
    expect(view.ok).toBe(true)
    if (view.ok) expect(view.application.answers).toHaveLength(QUESTIONNAIRE_DEFAULTS_MUTABLE.ru.length)
  })
})

describe("voting", () => {
  test("openVoting publishes the vote message into the group, not into private chats", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "u1", status: "UNREGISTERED" }))
    await fillDraft(deps, "tg-u1")
    const submitted = await deps.applications_.submit("tg-u1")
    if (!submitted.ok) throw new Error("submit failed")
    await deps.voting.openVoting(submitted.application)

    expect(deps.telegram.groupSent()).toHaveLength(1)
    // the group message holds the vote keyboard with live counts
    const keyboard = deps.telegram.groupSent()[0]?.keyboard
    const accept = keyboard?.[0]?.[0]
    const reject = keyboard?.[0]?.[1]
    expect(keyboard).toHaveLength(1)
    expect(accept?.text).toContain("Принять (0)")
    expect(accept?.style).toBe("success")
    expect(reject?.text).toContain("Отклонить (0)")
    expect(reject?.style).toBe("danger")
    // admins get a duplicate
    expect(deps.telegram.adminSent()).toHaveLength(2) // submit + voting announcements
  })

  test("vote from an active group member counts and updates the keyboard", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "applicant", status: "UNREGISTERED" }))
    await fillDraft(deps, "tg-applicant")
    const submitted = await deps.applications_.submit("tg-applicant")
    if (!submitted.ok) throw new Error("submit failed")
    await deps.voting.openVoting(submitted.application)

    deps.users.add(user({ uuid: "voter1", status: "ACTIVE", telegramId: "tg-voter1" }))
    deps.telegram.membership.set("tg-voter1", "member")

    const outcome = await deps.voting.castVote({
      applicationId: submitted.application.id,
      voterTelegramId: "tg-voter1",
      decision: "ACCEPT",
      callback: { callbackQueryId: "cbq1", chatId: -100_500 },
    })
    expect(outcome.ok).toBe(true)

    const counts = await deps.votes.countByDecision(submitted.application.id)
    expect(counts).toEqual({ votesFor: 1, votesAgainst: 0 })
    // ephemeral confirmation went out to the voter only
    expect(deps.telegram.ephemeralSent).toHaveLength(1)
    expect(deps.telegram.ephemeralSent[0]?.text).toContain("Голос принят")
  })

  test("self-vote is rejected", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "applicant", status: "UNREGISTERED" }))
    await fillDraft(deps, "tg-applicant")
    const submitted = await deps.applications_.submit("tg-applicant")
    if (!submitted.ok) throw new Error("submit failed")

    // the applicant cannot vote on their own application even when active
    await deps.users.setStatus("applicant", ["VOTING"], "ACTIVE")
    deps.telegram.membership.set("tg-applicant", "member")
    const outcome = await deps.voting.castVote({
      applicationId: submitted.application.id,
      voterTelegramId: "tg-applicant",
      decision: "ACCEPT",
    })
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("SELF_VOTE")
    expect(await deps.votes.countByDecision(submitted.application.id)).toEqual({ votesFor: 0, votesAgainst: 0 })
  })

  test("inactive accounts cannot vote", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "voter1", status: "SUSPENDED" }))
    const outcome = await deps.voting.castVote({
      applicationId: app.id,
      voterTelegramId: "tg-voter1",
      decision: "ACCEPT",
    })
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("NOT_ACTIVE")
  })

  test("voter outside the group cannot vote", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "voter1", status: "ACTIVE" }))
    // no membership record: getChatMember reports "left"
    const outcome = await deps.voting.castVote({
      applicationId: app.id,
      voterTelegramId: "tg-voter1",
      decision: "ACCEPT",
    })
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("NOT_IN_GROUP")
  })

  test("double vote is idempotent: same uuid, second telegram account of same player", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "voter1", status: "ACTIVE", telegramId: "tg-voter1" }))
    deps.users.add(user({ uuid: "voter1", status: "ACTIVE", telegramId: "tg-voter1-alt" }))
    for (const tg of ["tg-voter1", "tg-voter1-alt"]) deps.telegram.membership.set(tg, "member")

    const first = await deps.voting.castVote({
      applicationId: app.id,
      voterTelegramId: "tg-voter1",
      decision: "ACCEPT",
    })
    expect(first.ok).toBe(true)
    const second = await deps.voting.castVote({
      applicationId: app.id,
      voterTelegramId: "tg-voter1-alt",
      decision: "REJECT",
    })
    expect(second.ok).toBe(false)
    if (!second.ok) expect(second.reason).toBe("ALREADY_VOTED")
    // the first vote stands, counters unchanged
    expect(await deps.votes.countByDecision(app.id)).toEqual({ votesFor: 1, votesAgainst: 0 })
  })

  test("repeated identical callbacks do not change the result", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "voter1", status: "ACTIVE" }))
    deps.telegram.membership.set("tg-voter1", "member")

    for (let i = 0; i < 5; i++) {
      await deps.voting.castVote({
        applicationId: app.id,
        voterTelegramId: "tg-voter1",
        decision: "ACCEPT",
        callback: { callbackQueryId: `cbq-${i}`, chatId: -100_500 },
      })
    }
    expect(await deps.votes.countByDecision(app.id)).toEqual({ votesFor: 1, votesAgainst: 0 })
    // the repeated presses edit the existing ephemeral note instead of sending new ones
    expect(deps.telegram.ephemeralSent).toHaveLength(1)
    expect(deps.telegram.ephemeralEdited.length).toBe(4)
  })

  test("vote after expiry is rejected", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "voter1", status: "ACTIVE" }))
    deps.telegram.membership.set("tg-voter1", "member")
    deps.clock.advance(VOTING_MS + 1)
    const outcome = await deps.voting.castVote({
      applicationId: app.id,
      voterTelegramId: "tg-voter1",
      decision: "ACCEPT",
    })
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("EXPIRED")
  })
})

describe("voting expiry processing", () => {
  test("quorum reached -> decision published, applicant notified", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications, { eligibleVoters: 5, quorumState: { votesFor: 3, votesAgainst: 1 }, votes: deps.votes })
    deps.clock.advance(VOTING_MS + 1)
    const result = await deps.voting.decideExpired()
    expect(result.decisions).toHaveLength(1)
    expect(result.decisions[0]?.status).toBe("APPROVED")
    expect(result.decisions[0]?.quorumReached).toBe(true)
    const stored = await deps.applications.findById(app.id)
    expect(stored?.status).toBe("APPROVED")
    expect(stored?.voting.votesCast).toBe(4)
    // applicant notification + admin copy went out
    expect(deps.telegram.sent.some((s) => s.target === "user:tg-applicant" && s.effectId === "effect-1")).toBe(true)
    expect(deps.telegram.adminSent().some((text) => text.includes("Итог"))).toBe(true)
  })

  test("exactly 50% of eligible voted -> no quorum -> auto-extended once", async () => {
    const deps = build()
    // 2 of 4 voted: exactly 50%, no quorum
    const app = await seedVotingApplication(deps.applications, { eligibleVoters: 4, quorumState: { votesFor: 2, votesAgainst: 0 }, votes: deps.votes })
    await deps.voting.openVoting(app)
    deps.clock.advance(VOTING_MS + 1)
    const result = await deps.voting.decideExpired()
    expect(result.decisions).toHaveLength(0)
    expect(result.extended).toBe(1)
    const stored = await deps.applications.findById(app.id)
    expect(stored?.status).toBe("VOTING")
    expect(stored?.voting.extensionCount).toBe(1)
    // the vote message text was updated with the extension notice
    expect(deps.telegram.editedTexts.some((e) => e.text.includes("продлено"))).toBe(true)
    // admin chat got the extension notice too
    expect(deps.telegram.adminSent().some((text) => text.includes("продлено"))).toBe(true)
  })

  test("second shortfall decides EXPIRED and notifies admins for review", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications, { eligibleVoters: 4, quorumState: { votesFor: 1, votesAgainst: 0 }, votes: deps.votes })
    deps.users.add(user({ uuid: "applicant", status: "UNREGISTERED" }))
    await deps.users.setStatus("applicant", ["UNREGISTERED"], "VOTING")
    deps.clock.advance(VOTING_MS + 1)
    await deps.voting.decideExpired() // extends
    deps.clock.advance(VOTING_MS + 1)
    const second = await deps.voting.decideExpired()
    expect(second.decisions).toHaveLength(1)
    expect(second.decisions[0]?.status).toBe("EXPIRED")
    const stored = await deps.applications.findById(app.id)
    expect(stored?.status).toBe("EXPIRED")
    // admin notification about manual review
    expect(deps.telegram.adminSent().some((text) => text.includes("рассмотрение администрации"))).toBe(true)
    // the user status went back so a new application becomes possible later
    const applicant = await deps.users.findByUuid("applicant")
    expect(applicant?.status).toBe("UNREGISTERED")
  })

  test("catch-up on boot processes votings that expired while the bot was down", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications, { eligibleVoters: 3, quorumState: { votesFor: 2, votesAgainst: 0 }, votes: deps.votes })
    // the bot was down way past the expiry, even past an extension window
    deps.clock.advance(2 * VOTING_MS + HOUR)
    await deps.scheduler.runOnce()
    const stored = await deps.applications.findById(app.id)
    expect(stored?.status).toBe("APPROVED")
  })

  test("expired-but-already-decided votings are not processed twice", async () => {
    const deps = build()
    await seedVotingApplication(deps.applications, { eligibleVoters: 3, quorumState: { votesFor: 2, votesAgainst: 0 }, votes: deps.votes })
    deps.clock.advance(VOTING_MS + 1)
    await deps.voting.decideExpired()
    const again = await deps.voting.decideExpired()
    expect(again.decisions).toHaveLength(0)
    expect(again.extended).toBe(0)
  })
})

describe("group integration", () => {
  test("approved application gets a one-time invite named after the application", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications, { eligibleVoters: 3, quorumState: { votesFor: 2, votesAgainst: 0 }, votes: deps.votes })
    deps.users.add(user({ uuid: "applicant", status: "APPROVED" }))
    await deps.users.setStatus("applicant", ["UNREGISTERED"], "APPROVED")

    const outcome = await deps.group.issueInvite(app)
    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.invite.name).toBe(`adm-${app.id}`)

    const link = deps.telegram.createdLinks[0]
    expect(link?.memberLimit).toBe(1)
    expect(link?.name).toBe(`adm-${app.id}`)
    // the applicant received the link
    expect(deps.telegram.sent.some((s) => s.target === "user:tg-applicant" && s.text.includes(link!.name))).toBe(true)
  })

  test("join via the personal invite marks group-linked and activates the account", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "applicant", status: "APPROVED" }))
    await deps.users.setStatus("applicant", ["UNREGISTERED"], "APPROVED")
    await deps.group.issueInvite(app)

    const joined = await deps.group.handleJoin({
      chatId: -100_500,
      telegramId: "tg-applicant",
      inviteLinkName: `adm-${app.id}`,
    })
    expect(joined.ok).toBe(true)
    if (joined.ok) expect(joined.activated).toBe(true)

    const activated = await deps.users.findByUuid("applicant")
    expect(activated?.status).toBe("ACTIVE")
    expect(activated?.groupLinkedAt).not.toBeNull()
  })

  test("join with somebody else's invite is audited as an anomaly and ignored", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "applicant", status: "APPROVED" }))
    deps.users.add(user({ uuid: "stranger", status: "UNREGISTERED" }))
    await deps.group.issueInvite(app)

    const outcome = await deps.group.handleJoin({
      chatId: -100_500,
      telegramId: "tg-stranger",
      inviteLinkName: `adm-${app.id}`,
    })
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("INVITE_MISMATCH")
    expect(deps.audit.actionsOf("anomaly.group_invite_mismatch")).toHaveLength(1)
    // the applicant's invite is still unused
    const invite = await deps.invites.findByName(`adm-${app.id}`)
    expect(invite?.status).toBe("ISSUED")
  })

  test("invite is single-use: a second join with the same link is rejected", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "applicant", status: "APPROVED" }))
    await deps.group.issueInvite(app)

    const first = await deps.group.handleJoin({
      chatId: -100_500,
      telegramId: "tg-applicant",
      inviteLinkName: `adm-${app.id}`,
    })
    expect(first.ok).toBe(true)

    // even the same user cannot re-enter through the same link
    const second = await deps.group.handleJoin({
      chatId: -100_500,
      telegramId: "tg-applicant",
      inviteLinkName: `adm-${app.id}`,
    })
    expect(second.ok).toBe(false)
    if (!second.ok) expect(second.reason).toBe("INVITE_ALREADY_USED")
  })

  test("join without an invite name is an anomaly", async () => {
    const deps = build()
    const outcome = await deps.group.handleJoin({ chatId: -100_500, telegramId: "tg-x", inviteLinkName: null })
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("NO_INVITE_NAME")
    expect(deps.audit.actionsOf("anomaly.group_joined_without_invite")).toHaveLength(1)
  })

  test("activation requires both approval and group membership", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    // approved but invite was never issued / user never joined: status stays
    deps.users.add(user({ uuid: "applicant", status: "APPROVED" }))
    const outcome = await deps.group.handleJoin({
      chatId: -100_500,
      telegramId: "tg-applicant",
      inviteLinkName: `adm-${app.id}`,
    })
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("INVITE_NOT_FOUND")
    const user1 = await deps.users.findByUuid("applicant")
    expect(user1?.status).toBe("APPROVED")
  })

  test("account removal kicks via ban+unban pair and revokes unused invites", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "applicant", status: "ACTIVE", groupLinkedAt: new Date() }))
    await deps.group.issueInvite(app) // still unused

    const outcome = await deps.group.removeMember("applicant")
    expect(outcome.ok).toBe(true)
    if (!outcome.ok) return
    expect(outcome.kicked).toBe(true)
    expect(outcome.revokedInvites).toBe(1)
    // ban + immediate unban, the canonical kick pattern
    expect(deps.telegram.bans).toEqual(["tg-applicant"])
    expect(deps.telegram.unbans).toEqual(["tg-applicant"])
    expect(deps.telegram.revokedLinks).toHaveLength(1)
    const invite = await deps.invites.findByName(`adm-${app.id}`)
    expect(invite?.status).toBe("REVOKED")
  })

  test("group flows degrade gracefully without a configured group chat", async () => {
    const deps = build({ groupChatId: null })
    deps.users.add(user({ uuid: "applicant", status: "UNREGISTERED" }))
    await fillDraft(deps, "tg-applicant")
    const submitted = await deps.applications_.submit("tg-applicant")
    expect(submitted.ok).toBe(true)
    if (!submitted.ok) return
    // no group publication, but the voting itself still runs
    await deps.voting.openVoting(submitted.application)
    expect(deps.telegram.groupSent()).toHaveLength(0)

    const invite = await deps.group.issueInvite(submitted.application)
    expect(invite.ok).toBe(false)
    if (!invite.ok) expect(invite.reason).toBe("GROUP_NOT_CONFIGURED")

    const kick = await deps.group.removeMember(submitted.application.minecraftUuid)
    expect(kick.ok).toBe(false)
    if (!kick.ok) expect(kick.reason).toBe("GROUP_NOT_CONFIGURED")
  })

  test("membership check failure never lets a vote through", async () => {
    const deps = build()
    const app = await seedVotingApplication(deps.applications)
    deps.users.add(user({ uuid: "voter1", status: "ACTIVE" }))
    deps.telegram.membership.set("tg-voter1", "member")
    deps.telegram.membershipFailure = true
    const outcome = await deps.voting.castVote({
      applicationId: app.id,
      voterTelegramId: "tg-voter1",
      decision: "ACCEPT",
    })
    expect(outcome.ok).toBe(false)
    if (!outcome.ok) expect(outcome.reason).toBe("NOT_IN_GROUP")
  })
})

describe("audit trail", () => {
  test("application and voting lifecycle events are recorded", async () => {
    const deps = build()
    deps.users.add(user({ uuid: "applicant", status: "UNREGISTERED" }))
    await fillDraft(deps, "tg-applicant")
    const submitted = await deps.applications_.submit("tg-applicant")
    if (!submitted.ok) throw new Error("submit failed")
    await deps.voting.openVoting(submitted.application)

    const expected = [
      "application.draft_started",
      "application.submitted",
      "voting.created",
    ]
    for (const action of expected) {
      expect(deps.audit.actionsOf(action).length).toBe(1)
    }

    deps.users.add(user({ uuid: "voter1", status: "ACTIVE" }))
    deps.telegram.membership.set("tg-voter1", "member")
    await deps.voting.castVote({
      applicationId: submitted.application.id,
      voterTelegramId: "tg-voter1",
      decision: "ACCEPT",
    })
    expect(deps.audit.actionsOf("voting.vote_cast")).toHaveLength(1)
  })
})
