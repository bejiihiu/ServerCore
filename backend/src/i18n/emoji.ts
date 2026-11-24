import rawEmojiIds from "./emojis.json"
import { I18nError } from "./index.js"

/**
 * emoji registry: semantic keys resolve to custom emoji ids (pack tgiosicons)
 * with a unicode fallback for every entry. custom emoji entities in bot texts
 * only work when the bot owner has telegram premium (bot api 9.4) or the bot
 * owns a fragment username — so custom ids are opt-in via the feature flag and
 * every key degrades to its plain fallback otherwise.
 *
 * adding a new emoji = one line in emojis.json, keys are compile-checked.
 */
const entries = rawEmojiIds as Record<string, { customEmojiId: string; fallback: string }>

export type EmojiKey = keyof typeof rawEmojiIds

export type EmojiEntry = { customEmojiId: string; fallback: string }

export type ResolvedEmoji =
  | { kind: "custom"; id: string; fallback: string }
  | { kind: "unicode"; emoji: string }

export class EmojiRegistry {
  private readonly entries: Record<EmojiKey, EmojiEntry>

  constructor(private readonly allowCustom: boolean) {
    this.entries = structuredClone(entries) as Record<EmojiKey, EmojiEntry>
  }

  /** whether custom emoji entities are allowed at all (config flag) */
  get customAllowed(): boolean {
    return this.allowCustom
  }

  resolve(key: EmojiKey): ResolvedEmoji {
    const entry = this.entries[key]
    if (entry === undefined) {
      throw new I18nError(`unknown emoji key "${String(key)}"`, String(key))
    }
    if (this.allowCustom && entry.customEmojiId.length > 0) {
      return { kind: "custom", id: entry.customEmojiId, fallback: entry.fallback }
    }
    return { kind: "unicode", emoji: entry.fallback }
  }

  /** pack loader writes resolved ids here; unknown keys are rejected loudly */
  setCustomEmojiId(key: EmojiKey, id: string): void {
    const entry = this.entries[key]
    if (entry === undefined) {
      throw new I18nError(`unknown emoji key "${String(key)}"`, String(key))
    }
    this.entries[key] = { ...entry, customEmojiId: id }
  }

  /** fallback emoji char per key — the pack loader matches ids by this */
  fallbackOf(key: EmojiKey): string {
    const entry = this.entries[key]
    if (entry === undefined) {
      throw new I18nError(`unknown emoji key "${String(key)}"`, String(key))
    }
    return entry.fallback
  }
}

/**
 * renders registry entries into message text (tg-emoji html when custom is
 * available, plain fallback otherwise) and button icon ids (undefined when
 * custom is off — buttons then show text only).
 */
export interface EmojiRenderer {
  /** html fragment for message text, safe for parse_mode HTML */
  token(key: EmojiKey): string
  /** icon_custom_emoji_id for a button, or undefined when not available */
  iconId(key: EmojiKey): string | undefined
}

export function createEmojiRenderer(registry: EmojiRegistry): EmojiRenderer {
  return {
    token(key: EmojiKey): string {
      const resolved = registry.resolve(key)
      if (resolved.kind === "custom") {
        return `<tg-emoji emoji-id="${resolved.id}">${resolved.fallback}</tg-emoji>`
      }
      return resolved.emoji
    },
    iconId(key: EmojiKey): string | undefined {
      const resolved = registry.resolve(key)
      return resolved.kind === "custom" ? resolved.id : undefined
    },
  }
}
