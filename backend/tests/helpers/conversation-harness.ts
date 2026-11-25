import type { Conversation } from "@grammyjs/conversations"
import type { BotContext } from "../../src/bot/context.js"

/**
 * a scripted stand-in for grammY's conversation handle.
 *
 * it reproduces the four things our flows rely on: `external` (the replay-safe
 * escape hatch - on a resumed conversation its result comes from the log and
 * the side effect does not run again), `wait` / `waitForCallbackQuery` (an
 * update that does not match the awaited buttons fires `otherwise` and the
 * wait continues, exactly like the plugin), `now` (the replay-safe clock a
 * wait timeout is measured against) and the plugin's own acknowledge step.
 *
 * running out of scripted updates models an abandoned conversation: the real
 * bot would keep the handler parked until restart, the harness throws so the
 * test can prove no side effect happened.
 */

export type ConversationStep =
  /** a callback query carrying this data */
  | { kind: "callback"; data: string; advanceMs?: number }
  /** a plain text message */
  | { kind: "text"; text: string; advanceMs?: number }
  /** any update that is not the awaited button: `otherwise` fires, the wait repeats */
  | { kind: "other"; advanceMs?: number }

export type SentScreen = { text: string; other?: Record<string, unknown> }

export class ConversationAbandoned extends Error {
  constructor(readonly waitIndex: number) {
    super(`conversation abandoned at wait #${waitIndex}: nobody pressed anything`)
    this.name = "ConversationAbandoned"
  }
}

export type HarnessOptions = {
  telegramId?: number
  username?: string | null
  steps: ConversationStep[]
  /** replay log of a previous run's external results: served instead of re-running them */
  replayExternals?: unknown[]
  /** wall clock the harness starts at, ms (step `advanceMs` moves it forward) */
  startMs?: number
}

export type ConversationHarness = {
  conversation: Conversation<BotContext, BotContext>
  ctx: BotContext
  /** screens the flow replied with, in order */
  readonly sent: SentScreen[]
  /** callback data the flow acknowledged */
  readonly answered: string[]
  /** how many external side effects actually ran (0 on a replay) */
  readonly externalRuns: number
  /** values the externals produced, feed back as `replayExternals` to resume */
  readonly externalLog: unknown[]
}

/** a bot context that only records what the flows send */
function replyTarget(sent: SentScreen[]): { reply(text: string, other?: unknown): Promise<undefined> } {
  return {
    async reply(text: string, other?: unknown) {
      sent.push(other === undefined ? { text } : { text, other: other as Record<string, unknown> })
      return undefined
    },
  }
}

type Trigger = string | RegExp

function matchesTrigger(data: string, triggers: Trigger | Trigger[]): boolean {
  const list = Array.isArray(triggers) ? triggers : [triggers]
  return list.some((trigger) => (typeof trigger === "string" ? trigger === data : trigger.test(data)))
}

export function scriptedConversation(options: HarnessOptions): ConversationHarness {
  const telegramId = options.telegramId ?? 777_000_111
  const username = options.username === undefined ? "tg-user" : options.username
  const sent: SentScreen[] = []
  const answered: string[] = []
  const externalLog: unknown[] = []
  let externalRuns = 0
  let stepIndex = 0
  let externalIndex = 0
  let clockMs = options.startMs ?? 1_760_000_000_000

  const reply = replyTarget(sent)
  // outside the conversation only the raw fields the flows read exist
  const outer = {
    from: { id: telegramId, username },
    ...reply,
    async answerCallbackQuery() {
      // the builder's entry ctx carries the update that opened the flow, which
      // is a command in every flow we have: nothing to acknowledge there
    },
  } as unknown as BotContext

  /** the ctx a step hands back: an update plus the time it took to arrive */
  function delivered(step: ConversationStep): BotContext {
    clockMs += step.advanceMs ?? 0
    const update = {
      from: { id: telegramId, username },
      ...reply,
      async answerCallbackQuery() {
        if (step.kind === "callback") answered.push(step.data)
      },
    }
    if (step.kind === "callback") return { ...update, callbackQuery: { data: step.data } } as unknown as BotContext
    if (step.kind === "text") return { ...update, message: { text: step.text } } as unknown as BotContext
    return { ...update, message: {} } as unknown as BotContext
  }

  /** the replay-safe escape hatch: logged values are served on a resumed run */
  async function runExternal<T>(fn: (ctx: BotContext) => T | Promise<T>): Promise<T> {
    if (options.replayExternals !== undefined) {
      if (externalIndex >= options.replayExternals.length) {
        throw new Error(`replay log exhausted at external #${externalIndex}`)
      }
      return options.replayExternals[externalIndex++] as T
    }
    const value = await fn(outer)
    // the real plugin persists the value as json, so only plain data survives
    externalLog.push(value === undefined ? null : JSON.parse(JSON.stringify(value)))
    externalRuns++
    return value
  }

  const conversation = {
    external: runExternal,

    /** replay-safe wall clock: the plugin logs it like any other external */
    async now(): Promise<number> {
      return runExternal(() => clockMs)
    },

    async wait(): Promise<BotContext> {
      const index = stepIndex++
      const step = options.steps[index]
      if (step === undefined) throw new ConversationAbandoned(index)
      return delivered(step)
    },

    async waitForCallbackQuery(
      triggers: Trigger | Trigger[],
      waitOptions?: { otherwise?: (ctx: BotContext) => unknown },
    ): Promise<{ callbackQuery: { data: string }; answerCallbackQuery(): Promise<void> }> {
      // a rejected update is not an answer: the wait repeats, so the handler
      // sees the same sequence the plugin would replay
      for (;;) {
        const index = stepIndex++
        const step = options.steps[index]
        if (step === undefined) throw new ConversationAbandoned(index)
        if (step.kind !== "callback" || !matchesTrigger(step.data, triggers)) {
          await waitOptions?.otherwise?.(delivered(step))
          continue
        }
        const data = step.data
        return {
          callbackQuery: { data },
          async answerCallbackQuery() {
            answered.push(data)
          },
        }
      }
    },
  } as unknown as Conversation<BotContext, BotContext>

  return {
    conversation,
    ctx: outer,
    sent,
    answered,
    get externalRuns() {
      return externalRuns
    },
    externalLog,
  }
}
