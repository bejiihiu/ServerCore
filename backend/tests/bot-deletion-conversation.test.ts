import { describe, expect, test } from "bun:test"
import { CALLBACK } from "../src/bot/keyboards.js"
import { deleteAccountConversation, type DeleteConversationDeps } from "../src/bot/conversations/delete-account.js"
import type { AccountView } from "../src/modules/account/account-service.js"
import type { AccountDeletionService } from "../src/modules/account/deletion.js"
import { tr } from "../src/bot/ui/runtime.js"
import { scriptedConversation, type ConversationStep } from "./helpers/conversation-harness.js"

const account: AccountView = {
  minecraftUuid: "0f8a2b4c-1111-2222-3333-444455556666",
  username: "Steve",
  telegramId: "777000111",
  telegramUsername: "tg-user",
  telegramLinked: true,
  status: "ACTIVE",
  language: "ru",
  lastApplicationAt: null,
  createdAt: new Date(0),
}

function fakeDeletion(result: "ok" | "throw" = "ok") {
  const calls: Array<{ minecraftUuid: string; telegramId: string }> = []
  const deletion = {
    async deleteAccount(minecraftUuid: string, telegramId: string) {
      calls.push({ minecraftUuid, telegramId })
      if (result === "throw") throw new Error("pipeline step failed")
      return { outcome: "DELETED", steps: [] }
    },
  }
  return { deletion: deletion as unknown as AccountDeletionService, calls }
}

function deps(deletion: AccountDeletionService): DeleteConversationDeps {
  return { deletion }
}

async function run(steps: ConversationStep[], options: {
  deletion: AccountDeletionService
  replayExternals?: unknown[]
}) {
  const harness = scriptedConversation({
    steps,
    ...(options.replayExternals === undefined ? {} : { replayExternals: options.replayExternals }),
  })
  await deleteAccountConversation(harness.conversation, harness.ctx, deps(options.deletion), account)
  return harness
}

const confirm: ConversationStep = { kind: "callback", data: CALLBACK.deleteConfirm }
const cancel: ConversationStep = { kind: "callback", data: CALLBACK.deleteCancel }
const other: ConversationStep = { kind: "other" }

describe("account deletion conversation", () => {
  test("confirm deletes once and reports the account is gone", async () => {
    const fake = fakeDeletion()
    const harness = await run([confirm], { deletion: fake.deletion })

    expect(harness.sent[0]?.text).toContain(tr("ru").t("deletion.confirmTitle"))
    expect(harness.sent.at(-1)?.text).toContain(tr("ru").t("deletion.doneTitle"))
    // identity comes from the account view, not from the callback data
    expect(fake.calls).toEqual([{ minecraftUuid: account.minecraftUuid, telegramId: account.telegramId }])
    expect(harness.answered).toEqual([CALLBACK.deleteConfirm])
  })

  test("cancel never runs the pipeline", async () => {
    const fake = fakeDeletion()
    const harness = await run([cancel], { deletion: fake.deletion })

    expect(harness.sent.at(-1)?.text).toContain(tr("ru").t("deletion.cancelledTitle"))
    expect(fake.calls).toHaveLength(0)
    expect(harness.answered).toEqual([CALLBACK.deleteCancel])
  })

  test("a foreign update keeps the dialog open", async () => {
    const fake = fakeDeletion()
    const harness = await run([other, cancel], { deletion: fake.deletion })

    expect(harness.sent[1]?.text).toContain(tr("ru").t("deletion.nudge"))
    expect(fake.calls).toHaveLength(0)
    expect(harness.answered).toEqual([CALLBACK.deleteCancel])
  })

  test("a stale linking keyboard cannot confirm a deletion", async () => {
    const fake = fakeDeletion()
    const staleLink: ConversationStep = { kind: "callback", data: CALLBACK.linkConfirm }
    const harness = await run([staleLink, cancel], { deletion: fake.deletion })

    // the linking namespace is not the deletion namespace: nudge, no delete
    expect(harness.sent[1]?.text).toContain(tr("ru").t("deletion.nudge"))
    expect(fake.calls).toHaveLength(0)
  })

  test("a failed pipeline says the account waits in deletion-pending", async () => {
    const fake = fakeDeletion("throw")
    const harness = await run([confirm], { deletion: fake.deletion })

    expect(harness.sent.at(-1)?.text).toContain(tr("ru").t("deletion.failTitle"))
    expect(harness.sent.at(-1)?.text).toContain(tr("ru").t("deletion.failBody"))
    // the attempt happened exactly once, the retry is the user's move
    expect(fake.calls).toHaveLength(1)
    expect(harness.answered).toEqual([CALLBACK.deleteConfirm])
  })

  test("a resumed conversation does not delete a second time", async () => {
    const fake = fakeDeletion()
    const first = await run([confirm], { deletion: fake.deletion })
    expect(fake.calls).toHaveLength(1)

    const resumed = await run([confirm], { deletion: fake.deletion, replayExternals: first.externalLog })

    expect(fake.calls).toHaveLength(1)
    expect(resumed.externalRuns).toBe(0)
    expect(resumed.sent.map((s) => s.text)).toEqual(first.sent.map((s) => s.text))
  })

  test("an abandoned confirmation deletes nothing", async () => {
    const fake = fakeDeletion()

    await expect(run([], { deletion: fake.deletion })).rejects.toThrow("conversation abandoned")

    expect(fake.calls).toHaveLength(0)
  })
})
