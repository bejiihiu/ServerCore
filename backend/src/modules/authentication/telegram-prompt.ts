import type { Api } from "grammy"
import type { Logger } from "../../logging/logger.js"
import { i18n } from "../../i18n/instance.js"
import type { Translator } from "../../bot/ui/text/index.js"
import { bold, expandableQuote, joinLines } from "../../bot/ui/text/index.js"
import { actionRow, callbackData, type InlineKeyboardMarkup } from "../../bot/ui/keyboards/index.js"
import { createTranslator } from "../../bot/ui/text/index.js"
import { editScreen, sendScreen } from "../../bot/ui/sending.js"
import type { ScreenMessage } from "../../bot/ui/screens/types.js"
import type { PromptRequest, PromptSender } from "./auth-service.js"

/** callback scheme <ns>:<action>:<arg>, see the bot-ui design doc */
const CALLBACK_PREFIX = "auth"

export type ParsedAuthCallback =
  | { kind: "APPROVE"; token: string }
  | { kind: "REJECT"; token: string }
  | null

/** parses "auth:approve:<token>" / "auth:reject:<token>" callback payloads */
export function parseAuthCallbackData(data: string | undefined): ParsedAuthCallback {
  if (data === undefined) return null
  const parts = data.split(":")
  if (parts[0] !== CALLBACK_PREFIX || parts[2] === undefined) return null
  if (parts[1] === "approve") return { kind: "APPROVE", token: parts[2] }
  if (parts[1] === "reject") return { kind: "REJECT", token: parts[2] }
  return null
}

export function approveCallbackData(token: string): string {
  return callbackData(CALLBACK_PREFIX, "approve", token)
}

export function rejectCallbackData(token: string): string {
  return callbackData(CALLBACK_PREFIX, "reject", token)
}

type SentPrompt = { chatId: string; messageId: number }

/**
 * telegram side of the auth flow: renders the confirmation prompt through the
 * shared bot-ui layer (style-styled buttons, expandable blockquote, premium
 * fallback handled by sending.ts) and rewrites the prompt after a decision so
 * the buttons cannot be pressed twice.
 */
export class TelegramAuthUi {
  private api: Api | null
  private readonly logger: Logger
  private readonly messageEffectId?: string
  private readonly serverName: string
  private readonly translator: Translator

  /** requestId -> where the prompt lives, for the decision edits */
  private readonly sent = new Map<string, SentPrompt>()

  constructor(deps: {
    api: Api | null
    logger: Logger
    serverName: string
    messageEffectId?: string
  }) {
    this.api = deps.api
    this.logger = deps.logger
    this.serverName = deps.serverName
    this.messageEffectId = deps.messageEffectId
    this.translator = createTranslator(i18n)
  }

  /** swaps the api after the bot is built (mirrors NotificationService) */
  attachApi(api: Api | null): void {
    this.api = api
    if (api === null) this.sent.clear()
  }

  /** bound PromptSender: false/throw means telegram was unreachable */
  readonly send: PromptSender = async (request) => {
    const api = this.api
    if (api === null) return false

    const screen = promptScreen(request, this.serverName, this.translator)
    const message = await sendScreen(api, {
      chatId: request.telegramId,
      screen,
      ...(this.messageEffectId !== undefined ? { effectId: this.messageEffectId } : {}),
    })
    this.sent.set(request.id, {
      chatId: String(request.telegramId),
      messageId: message.message_id,
    })
    return true
  }

  /**
   * rewrites the prompt after a decision: buttons gone, outcome text in.
   * telegram errors here are logged, never thrown — the decision itself is
   * already final at this point.
   */
  async conclude(requestId: string, outcomeKey: AuthOutcomeKey, player: string): Promise<void> {
    const where = this.sent.get(requestId)
    this.sent.delete(requestId)
    const api = this.api
    if (where === undefined || api === null) return
    const screen: ScreenMessage = {
      text: outcomeText(outcomeKey, player, this.translator),
    }
    try {
      await editScreen(api, { chatId: where.chatId, messageId: where.messageId, screen })
    } catch (error) {
      this.logger.warn({ err: error, requestId }, "could not rewrite the auth prompt")
    }
  }

  /** drops prompt locations for requests that will never conclude */
  forget(requestId: string): void {
    this.sent.delete(requestId)
  }
}

export type AuthOutcomeKey =
  | "approved"
  | "rejected"
  | "alreadyProcessed"
  | "expired"
  | "notYours"
  | "unknown"
  | "noActiveSession"
  | "staleSession"
  | "rateLimited"

const OUTCOME_KEYS: Record<AuthOutcomeKey, string> = {
  approved: "auth.approved",
  rejected: "auth.rejected",
  alreadyProcessed: "auth.alreadyProcessed",
  expired: "auth.expired",
  notYours: "auth.notYours",
  unknown: "auth.unknown",
  noActiveSession: "auth.noActiveSession",
  staleSession: "auth.staleSession",
  rateLimited: "auth.rateLimited",
}

export function outcomeText(key: AuthOutcomeKey, player: string, t: Translator): string {
  return t.t(OUTCOME_KEYS[key], { player })
}

/** the rich prompt: title, player/server facts, window, expandable info block */
export function promptScreen(request: PromptRequest, serverName: string, t: Translator): ScreenMessage {
  const markup: InlineKeyboardMarkup = {
    inline_keyboard: [
      actionRow([
        {
          label: t.t("auth.approve"),
          callbackData: approveCallbackData(request.token),
          style: "success",
        },
        {
          label: t.t("auth.reject"),
          callbackData: rejectCallbackData(request.token),
          style: "danger",
        },
      ]),
    ],
  }
  return {
    text: joinLines(
      bold(t.t("auth.title")),
      "",
      t.t("auth.player", { player: request.username }),
      t.t("auth.server", { server: serverName }),
      "",
      t.t("auth.detected"),
      t.t("auth.window", { seconds: request.expiresInSeconds }),
      expandableQuote(t.t("auth.info")),
    ),
    markup,
  }
}
