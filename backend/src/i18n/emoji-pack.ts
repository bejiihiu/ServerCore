import type { EmojiRegistry } from "./emoji.js"
import type { EmojiKey } from "./emoji.js"

/**
 * loads custom emoji ids for the registry from a telegram emoji pack
 * (tgiosicons). ids are stable across bots and over time, so the result is
 * cached for the process lifetime. matching is done by the fallback emoji
 * char recorded in emojis.json. best-effort: on any api failure the registry
 * simply keeps unicode fallbacks.
 */

/** minimal structural slice of the grammY Api the loader needs */
export type StickerSetApi = {
  getStickerSet(name: string): Promise<{
    stickers: ReadonlyArray<{ emoji?: string; custom_emoji_id?: string }>
  }>
}

const packCache = new Map<string, Map<string, string>>()

/** emoji char -> custom_emoji_id for one pack, cached forever (ids are stable) */
export async function loadPackIndex(api: StickerSetApi, packName: string): Promise<Map<string, string>> {
  const cached = packCache.get(packName)
  if (cached !== undefined) return cached

  const set = await api.getStickerSet(packName)
  const index = new Map<string, string>()
  for (const sticker of set.stickers) {
    if (sticker.emoji !== undefined && sticker.custom_emoji_id !== undefined) {
      index.set(sticker.emoji, sticker.custom_emoji_id)
    }
  }
  packCache.set(packName, index)
  return index
}

/**
 * fills the registry with custom ids from a pack. returns how many keys got
 * an id. keys whose fallback char is not in the pack stay on unicode fallback.
 */
export async function applyEmojiPack(
  registry: EmojiRegistry,
  api: StickerSetApi,
  packName: string,
  keys: readonly EmojiKey[],
): Promise<number> {
  if (!registry.customAllowed) return 0
  const index = await loadPackIndex(api, packName)
  let applied = 0
  for (const key of keys) {
    const id = index.get(registry.fallbackOf(key))
    if (id !== undefined) {
      registry.setCustomEmojiId(key, id)
      applied += 1
    }
  }
  return applied
}
