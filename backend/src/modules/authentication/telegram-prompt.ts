import type { Api } from "grammy"
import type { Logger } from "../../logging/logger.js"
import type { Locale } from "../../services/i18n.js"
import { t } from "../../services/i18n.js"
import type { PromptRequest, PromptSender } from "./auth-service.js"

/** callback_data prefixes, 18 bytes total, well inside the 64-byte limit */
const APPROVE_PREFIX = "a:"
const REJECT_PREFIX = "r:"

export type ParsedAuthCallback =
  | { kind: "APPROVE"; token: string }
  | { kind: "REJECT"; token: string }
  | null

/** parses "a:<token>" / "r:<token>" callback payloads */
export function parseAuthCallbackData(data: string | undefined): ParsedAuthCallback {
  if (data === undefined) return null
  if (data.startsWith(APPROVE_PREFIX)) return { kind: "APPROVE", token: data.slice(APPROVE_PREFIX.length) }
  if (data.startsWith(REJECT_PREFIX)) return { kind: "REJECT", token: data.slice(REJECT_PREFIX.length) }
  return null
}

type SentPrompt = { chatId: string; messageId: number; locale: Locale }

/**
 * telegram side of the auth flow: sends the rich confirmation prompt
 * (inline keyboard with button styles, message effect, expandable blockquote
 * for the server info) and rewrites the prompt after a decision so the
 * buttons cannot be pressed twice.
 */
export class TelegramAuthUi implements PromptSender {
  private api: Api | null
  private readonly logger: Logger
  private readonly defaultLocale: Locale
  private readonly messageEffectId?: string
  private readonly serverName: string

  /** requestId -> where the prompt lives, for the decision edits */
  private readonly sent = new Map<string, SentPrompt>()

  constructor(deps: {
    api: Api | null
    logger: Logger
    defaultLocale: Locale
    serverName: string
    messageEffectId?: string
  }) {
    this.api = deps.api
    this.logger = deps.logger
    this.defaultLocale = deps.defaultLocale
    this.serverName = deps.serverName
    this.messageEffectId = deps.messageEffectId
  }

  /** swaps the api after the bot is built (mirrors NotificationService) */
  attachApi(api: Api | null): void {
    this.api = api
    if (api === null) this.sent.clear()
  }

  /** PromptSender impl: false/throw means telegram was unreachable */
  async send(request: PromptRequest): Promise<boolean> {
    const api = this.api
    if (api === null) return false

    const locale = this.defaultLocale
    const text = promptText(request, this.serverName, locale)
    const response = await api.sendMessage(request.telegramId, text, {
      parse_mode: "HTML",
      link_preview_options: { is_disabled: true },
      ...(this.messageEffectId !== undefined ? { message_effect_id: this.messageEffectId } : {}),
      reply_markup: {
        inline_keyboard: [
          [
            { text: t(locale, "auth.btn.approve"), callback_data: `${APPROVE_PREFIX}${request.token}`, style: "success" },
            { text: t(locale, "auth.btn.reject"), callback_data: `${REJECT_PREFIX}${request.token}`, style: "danger" },
          ],
        ],
      },
    })
    this.sent.set(request.id, {
      chatId: String(request.telegramId),
      messageId: response.message_id,
      locale,
    })
    return true
  }

  /**
   * rewrites the prompt after a decision: buttons gone, outcome text in.
   * telegram errors here are logged, never thrown — the decision itself is
   * already final at this point.
   */
  async conclude(requestId: string, telegramId: string, outcomeKey: AuthOutcomeKey, player: string): Promise<void> {
    const where = this.sent.get(requestId)
    this.sent.delete(requestId)
    const api = this.api
    if (where === undefined || api === null) return
    try {
      await api.editMessageText(where.chatId, where.messageId, outcomeText(outcomeKey, player, where.locale), {
        parse_mode: "HTML",
        link_preview_options: { is_disabled: true },
      })
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
  | "auth.cb.approved"
  | "auth.cb.rejected"
  | "auth.cb.already_processed"
  | "auth.cb.expired"
  | "auth.cb.not_yours"
  | "auth.cb.unknown"
  | "auth.cb.no_active_session"
  | "auth.cb.stale_session"
  | "auth.cb.rate_limited"

export function outcomeText(key: AuthOutcomeKey, player: string, locale: Locale): string {
  return t(locale, key, { player })
}

/** the rich prompt: title, player/server facts, window, expandable info block */
export function promptText(request: PromptRequest, serverName: string, locale: Locale): string {
  return [
    t(locale, "auth.prompt.title"),
    "",
    t(locale, "auth.prompt.player", { player: escapeHtml(request.username) }),
    t(locale, "auth.prompt.server", { server: escapeHtml(serverName) }),
    "",
    t(locale, "auth.prompt.detected"),
    t(locale, "auth.prompt.window", { seconds: request.expiresInSeconds }),
    `<blockquote expandable>${t(locale, "auth.prompt.info")}</blockquote>`,
  ].join("\n")
}

/** telegram html mode: only <, > and & need escaping */
function escapeHtml(raw: string): string {
  return raw.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
}
