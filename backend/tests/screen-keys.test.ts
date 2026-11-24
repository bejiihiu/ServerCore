import { describe, expect, test } from "bun:test"
import { readFileSync, readdirSync } from "node:fs"
import { join } from "node:path"
import ruDict from "../src/i18n/locales/ru.json"
import enDict from "../src/i18n/locales/en.json"

/**
 * currency guard for the screen layer: every literal i18n key the source
 * reaches for must exist in both locale files. a typo here does not fail the
 * typecheck (keys are strings) and only throws when that exact screen is
 * rendered — which is how a single broken screen ships unnoticed.
 *
 * dynamic keys (`link.${reason}`) are out of scope: they are covered by the
 * builders' own tests, one path per branch.
 */

type Dict = { readonly [key: string]: string | Dict }

const SRC = join(import.meta.dir, "..", "src")

function sourceFiles(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) out.push(...sourceFiles(path))
    else if (path.endsWith(".ts")) out.push(path)
  }
  return out
}

function has(dict: Dict, key: string): boolean {
  let node: string | Dict | undefined = dict
  for (const part of key.split(".")) {
    if (typeof node !== "object" || node === null) return false
    node = node[part]
  }
  return typeof node === "string"
}

/** plural groups are matched through their fallback form, like tPlural does */
function resolves(dict: Dict, key: string): boolean {
  return has(dict, key) || has(dict, `${key}.other`)
}

/** every literal key used as t.t("...") or tPlural("...") in the bot layer */
function usedKeys(): Map<string, string> {
  const used = new Map<string, string>()
  for (const file of sourceFiles(SRC)) {
    const text = readFileSync(file, "utf8")
    for (const match of text.matchAll(/\b(?:t\.t|t\.tPlural|tr\([^)]*\)\.t)\(\s*"([a-zA-Z][\w.]*)"/g)) {
      used.set(match[1]!, file.replace(SRC, "src"))
    }
    for (const match of text.matchAll(/\btPlural\(\s*"([a-zA-Z][\w.]*)"/g)) {
      used.set(match[1]!, file.replace(SRC, "src"))
    }
  }
  return used
}

describe("screen key currency", () => {
  const used = usedKeys()

  test("the scan actually finds keys (guards against a dead regex)", () => {
    expect(used.size).toBeGreaterThan(40)
    expect(used.has("common.back")).toBe(true)
    expect(used.has("help.title")).toBe(true)
  })

  test("every literal key resolves in russian", () => {
    const missing = [...used].filter(([key]) => !resolves(ruDict as Dict, key)).map(([key, file]) => `${key} (${file})`)
    expect(missing).toEqual([])
  })

  test("every literal key resolves in english", () => {
    const missing = [...used].filter(([key]) => !resolves(enDict as Dict, key)).map(([key, file]) => `${key} (${file})`)
    expect(missing).toEqual([])
  })
})
