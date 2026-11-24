import { describe, expect, test } from "bun:test"
import {
  bold,
  code,
  composeText,
  escapeHtml,
  expandableQuote,
  italic,
  joinLines,
  kv,
  link,
  quote,
  section,
  spoiler,
  strikethrough,
  stripCustomEmoji,
  tgEmoji,
  underline,
} from "../src/bot/ui/text/index.js"

describe("escaping", () => {
  test("escapes html specials", () => {
    expect(escapeHtml('a < b & c > "d"')).toBe("a &lt; b &amp; c &gt; \"d\"")
  })

  test("wrappers escape their content", () => {
    expect(bold("<nick>")).toBe("<b>&lt;nick&gt;</b>")
    expect(italic("<i>")).toBe("<i>&lt;i&gt;</i>")
    expect(underline("<u>")).toBe("<u>&lt;u&gt;</u>")
    expect(strikethrough("<s>")).toBe("<s>&lt;s&gt;</s>")
    expect(code("<x>")).toBe("<code>&lt;x&gt;</code>")
    expect(quote("<q>")).toBe("<blockquote>&lt;q&gt;</blockquote>")
    expect(expandableQuote("<q>")).toBe("<blockquote expandable>&lt;q&gt;</blockquote>")
    expect(spoiler("<secret>")).toBe("<tg-spoiler>&lt;secret&gt;</tg-spoiler>")
  })

  test("link escapes both text and href", () => {
    expect(link("ткни", 'http://x/?a=1&b=<2>')).toBe('<a href="http://x/?a=1&amp;b=&lt;2&gt;">ткни</a>')
  })

  test("kv escapes value, keeps label bold", () => {
    expect(kv("Ник", "<cheater>")).toBe("<b>Ник</b> &lt;cheater&gt;")
  })
})

describe("composition", () => {
  test("joinLines drops empty fragments", () => {
    expect(joinLines("a", "", false, undefined, "b")).toBe("a\nb")
    expect(joinLines()).toBe("")
  })

  test("kv and section produce consistent markup", () => {
    expect(section("Статус")).toBe("<b>Статус</b>")
  })

  test("composeText pins html and disables previews", () => {
    expect(composeText()).toEqual({ parse_mode: "HTML", link_preview_options: { is_disabled: true } })
  })
})

describe("custom emoji tokens", () => {
  test("tgEmoji builds the api 9.4 tag", () => {
    expect(tgEmoji("5368324170671202286", "👍")).toBe('<tg-emoji emoji-id="5368324170671202286">👍</tg-emoji>')
  })

  test("strip is the inverse of tgEmoji", () => {
    const tagged = tgEmoji("42", "🟢")
    expect(stripCustomEmoji(tagged)).toBe("🟢")
  })
})
