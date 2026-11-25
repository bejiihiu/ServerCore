import { describe, expect, test } from "bun:test"
import { CALLBACK } from "../src/bot/keyboards.js"
import { linkingConversation, type LinkingConversationDeps } from "../src/bot/conversations/linking.js"
import type { AccountService, AccountView, LinkFailureReason, LinkOutcome } from "../src/modules/account/account-service.js"
import { tr } from "../src/bot/ui/runtime.js"
import { scriptedConversation, type ConversationStep } from "./helpers/conversation-harness.js"

const TOKEN = "one-time-token"
const USERNAME = "Steve"

type LinkCall = { telegramId: string; telegramUsername: string | null; token: string }

function fakeAccounts(opts: {
  existing?: Pick<AccountView, "language"> | null
  outcome?: LinkOutcome
} = {}) {
  const calls: LinkCall[] = []
  const accounts = {
    async getByTelegramId() {
      return opts.existing ?? null
    },
    async linkTelegram(params: LinkCall) {
      calls.push(params)
      return opts.outcome ?? { linked: true, username: USERNAME, idempotent: false }
    },
  }
  return { accounts: accounts as unknown as AccountService, calls }
}

function deps(accounts: AccountService, fallbackLanguage: "ru" | "en" = "ru"): LinkingConversationDeps {
  return { accounts, fallbackLanguage }
}

async function run(steps: ConversationStep[], options: {
  accounts: AccountService
  fallbackLanguage?: "ru" | "en"
  replayExternals?: unknown[]
}) {
  const harness = scriptedConversation({
    steps,
    ...(options.replayExternals === undefined ? {} : { replayExternals: options.replayExternals }),
  })
  await linkingConversation(harness.conversation, harness.ctx, deps(options.accounts, options.fallbackLanguage ?? "ru"), TOKEN, USERNAME)
  return harness
}

const confirm: ConversationStep = { kind: "callback", data: CALLBACK.linkConfirm }
const cancel: ConversationStep = { kind: "callback", data: CALLBACK.linkCancel }
const other: ConversationStep = { kind: "other" }

describe("linking conversation", () => {
  test("confirm runs the link once and reports success", async () => {
    const fake = fakeAccounts()
    const harness = await run([confirm], { accounts: fake.accounts })

    expect(harness.sent[0]?.text).toContain(tr("ru").t("link.confirmTitle"))
    expect(harness.sent.at(-1)?.text).toContain(tr("ru").t("link.successTitle"))
    expect(fake.calls).toEqual([{ telegramId: "777000111", telegramUsername: "tg-user", token: TOKEN }])
    expect(harness.answered).toEqual([CALLBACK.linkConfirm])
    expect(harness.externalRuns).toBe(3) // from, locale, link
  })

  test("cancel answers the button and never touches the link pipeline", async () => {
    const fake = fakeAccounts()
    const harness = await run([cancel], { accounts: fake.accounts })

    expect(harness.sent.at(-1)?.text).toContain(tr("ru").t("link.cancelledTitle"))
    expect(fake.calls).toHaveLength(0)
    expect(harness.answered).toEqual([CALLBACK.linkCancel])
    expect(harness.externalRuns).toBe(2) // the account read happened, the link did not
  })

  test("a foreign update is nudged and the wait continues", async () => {
    const fake = fakeAccounts()
    const harness = await run([other, confirm], { accounts: fake.accounts })

    expect(harness.sent[1]?.text).toContain(tr("ru").t("link.nudge"))
    expect(harness.sent.at(-1)?.text).toContain(tr("ru").t("link.successTitle"))
    expect(fake.calls).toHaveLength(1)
    // only the real button press was acknowledged
    expect(harness.answered).toEqual([CALLBACK.linkConfirm])
  })

  test("a button from another flow counts as a non-answer", async () => {
    const fake = fakeAccounts()
    // a stale deletion keyboard in the same chat must not confirm a link
    const staleDelete: ConversationStep = { kind: "callback", data: CALLBACK.deleteConfirm }
    const harness = await run([staleDelete, cancel], { accounts: fake.accounts })

    expect(harness.sent[1]?.text).toContain(tr("ru").t("link.nudge"))
    expect(harness.answered).toEqual([CALLBACK.linkCancel])
    expect(fake.calls).toHaveLength(0)
  })

  test("every failure reason maps to its own screen", async () => {
    const cases: Array<[LinkFailureReason, string]> = [
      ["TOKEN_INVALID", "link.fail.token"],
      ["ACCOUNT_FROZEN", "link.fail.frozen"],
      ["ALREADY_LINKED", "link.fail.alreadyLinked"],
      ["OWNERSHIP_NOT_PROVEN", "link.fail.ownership"],
      ["SERVER_OFFLINE", "link.fail.offline"],
      ["RATE_LIMITED", "link.fail.rateLimited"],
      ["ACCOUNT_NOT_FOUND", "link.fail.notFound"],
    ]
    for (const [reason, key] of cases) {
      const fake = fakeAccounts({ outcome: { linked: false, reason } })
      const harness = await run([confirm], { accounts: fake.accounts })

      const last = harness.sent.at(-1)?.text ?? ""
      expect(last).toContain(tr("ru").t("link.failTitle"))
      expect(last).toContain(tr("ru").t(key))
    }
  })

  test("the account language wins over the ops default", async () => {
    const fake = fakeAccounts({ existing: { language: "en" } })
    const harness = await run([cancel], { accounts: fake.accounts })

    expect(harness.sent[0]?.text).toContain(tr("en").t("link.confirmTitle"))
    expect(harness.sent.at(-1)?.text).toContain(tr("en").t("link.cancelledTitle"))
  })

  test("an account that does not exist yet uses the ops default", async () => {
    const fake = fakeAccounts({ existing: null })
    const harness = await run([cancel], { accounts: fake.accounts, fallbackLanguage: "en" })

    expect(harness.sent[0]?.text).toContain(tr("en").t("link.confirmTitle"))
  })

  test("a resumed conversation replays the decision without linking twice", async () => {
    const fake = fakeAccounts()
    const first = await run([confirm], { accounts: fake.accounts })
    expect(fake.calls).toHaveLength(1)

    // restart + resume: the external log is served from storage, so the same
    // screens come back and the pipeline is not entered again
    const resumed = await run([confirm], { accounts: fake.accounts, replayExternals: first.externalLog })

    expect(fake.calls).toHaveLength(1)
    expect(resumed.externalRuns).toBe(0)
    expect(resumed.sent.map((s) => s.text)).toEqual(first.sent.map((s) => s.text))
  })

  test("an abandoned conversation leaves no side effect behind", async () => {
    const fake = fakeAccounts()

    await expect(run([], { accounts: fake.accounts })).rejects.toThrow("conversation abandoned")

    expect(fake.calls).toHaveLength(0)
  })
})
