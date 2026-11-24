# POLISH — final UI/UX polish pass checklist

The design system (`plugin/src/.../ui/`, `docs/ui-design-system.md`) was built early so every feature could lean on it. This checklist is for the **final polish pass**, run once after all feature phases merge. It is deliberately generic: name the flow, not the file — features were built in parallel and their internal layout may shift before the pass.

## 0. Preconditions

- [ ] All feature phases merged; no in-flight worktrees touch `plugin/`
- [ ] `./gradlew build` green, theme token tests green
- [ ] `docs/ui-design-system.md` re-read end to end; tokens unchanged or updated first

## 1. Component inventory

Every player-facing surface in the final build should resolve to a toolkit component. Walk the flows and check each surface:

- [ ] **Arrival flow** (join → status → auth decision): title staged after the world renders, status card scrolls-back-worthy, prompt pulse capped, bossbar cleared on exit — no orphan bars after quit/kick
- [ ] **Auth lifecycle** (requested → approved → rejected → expired): each state has exactly one surface, transitions never stack two titles at once
- [ ] **Guest lifecycle** (started → restricted action → session nearing limit → ended): limit warnings arrive before the cutoff, not on it
- [ ] **Update/restart announcements** (available → downloading → restart now/later): countdown surfaces exist, console and in-game text agree
- [ ] **Admission events** (application states): informational banners use the right semantic kind, never `DANGER` for neutral news
- [ ] **Admin/observation surfaces**: distinct register (muted, no brand flourish), no leaks into player-visible chat
- [ ] Every error path renders a real message from the locale store — no raw keys, no stack traces in chat

## 2. Palette and consistency checks

- [ ] Grep the whole plugin for vanilla color names and `ChatColor`/`§` — zero hits outside the theme file
- [ ] Grep for raw hex literals in feature code — all colors resolve to `Theme` tokens
- [ ] One accent per surface: no surface uses more than one semantic color unless the semantics differ
- [ ] Rules/rails/marks use the fixed glyph vocabulary (`┃ ─ ▌ ◆ ▸ ▹`) and `FRAME_WIDTH`/`CARD_WIDTH` — no ad-hoc borders
- [ ] Bossbar colors follow the yellow→red urgency rule everywhere
- [ ] Title timing follows the conventions (fast fade in, ~2.6s stay, fast out); countdown ticks at 900ms stay
- [ ] No two overlapping surfaces at once beyond the allowed pairings (title+card ok, title+actionbar+bossbar+chat all at once is not)

## 3. Copy candidates to revisit

These were written against early assumptions; re-read them against the final mechanics and tighten:

- [ ] Guest welcome card — session counting wording (does the limit semantics match the shipped one?)
- [ ] Guest restriction reminders — the "chat is closed" line is only true while chat stays fully disabled for guests
- [ ] Auth prompt card/pulse/window — window length wording vs the real backend TTL; expired-banner tone
- [ ] Countdown tick subtitle ("через мгновение") — replace if the final flow gives it a concrete meaning
- [ ] Tab list header/footer — season wording ("второй заход") checked against the actual season state
- [ ] Update/restart copy — whatever the ops flow added on the fly
- [ ] Sweep: no exclamation marks, no trailing periods on short lines, no greeting-style headlines, no unverified claims, no hype words — RU copy reads like a gatekeeper, not a billboard

## 4. Integration seams (generic)

Feature flows consume the toolkit through these entry points; verify each integration kept the contract:

- [ ] All in-game rendering goes through `UiKit` (or `UiFx` for custom geometry) — no feature packet-sends around the toolkit
- [ ] Locale templates ship keys, callers ship values; no feature hardcodes RU strings in code
- [ ] Dynamic values (names, codes, timers) always arrive as placeholders — escaped at injection, never concatenated into templates
- [ ] Feature scheduling uses the entity/region scheduler wrappers; re-audit anything that grew its own scheduler code
- [ ] Session/account state flows hand real values into `guestWelcome`/`authPrompt` — demo defaults (session 1/3, generated code) must be gone
- [ ] New WS message types that render player text follow the envelope and are documented in `docs/websocket-protocol.md`

## 5. Anti-slop audit (Minecraft edition)

- [ ] No emoji in any surface; markers come from the glyph vocabulary
- [ ] No rainbow text, no gradient washes, no decorative glow — color means something everywhere
- [ ] No stacked identical cards; lists vary rhythm or get merged
- [ ] No "success theater": routine confirmations stay one quiet line or don't render at all
- [ ] Nothing pulses unless it is a live state
- [ ] Template check: swap the server name for another closed-server brand — if every message still fits verbatim, the copy is generic; push it back toward specifics of this season
- [ ] Readability pass in real clients: check frames at default chat width and at GUI scale 2; no line wraps mid-rail

---

# Bot polish — telegram UI/UX pass

the sections above cover the in-game (plugin) surfaces. the checklist below
covers the telegram bot: design system in `backend/src/bot/ui/DESIGN.md`,
toolkit in `backend/src/bot/ui/`, i18n in `backend/src/i18n/`. keep entries
generic (flow names, not files) — implementations move.

## B1. screen inventory audit

- [x] every user-facing screen uses a builder from `screens/` — no flow
      builds message text with bare template strings (`noticeScreen` is the
      catch-all shell for success/failure/info states)
- [x] every screen opens with exactly one semantic dot (status.active /
      pending / attention / neutral) matching the screen's verdict
- [x] every screen has a back row (except the main menu and notifications)
- [x] no screen renders a dead end: every button leads to a registered
      handler, every disabled state resolves within one interaction; buttons
      of a finished conversation are answered by a namespace catch-all
- [x] long texts (rules, logs, questionnaires) render through
      `expandableQuote`, not as walls of text
- [x] sensitive values (tokens, ids, contacts) are wrapped in `spoiler` —
      nothing user-facing prints a raw uuid anymore

## B2. account flows (linking, card, unlink)

- [x] linking progress screens use `loadingScreen` between steps, and the
      confirmation is a real confirm dialog (cancel first, confirm second)
- [x] account card fields follow `kv()` rows; role/date omitted when absent,
      never rendered as "—"
- [x] unlink confirmation uses `confirmDialogScreen` with tone "danger"
- [x] deep-link failure texts come from one `start.badToken*` block, used by
      both the payload and the preview path
- [x] the "привязка через ссылку из игры" hint is one sentence, identical
      everywhere it appears, and shown only where it is actionable

## B3. admission flows (questionnaire, group voting)

- [x] questionnaire steps use one numbered section label pattern
- [x] voting screens in the group use ephemeral replies (bot api 10.2) for
      private confirmations to the voter; the public message carries only
      the vote buttons
- [x] vote buttons: style "success" for approve, "danger" for reject
- [ ] quorum/deadline info as `kv` rows + `date_time` entity — the group card
      is plain text (broadcast-style channel) and still prints МСК; moving it
      to entities needs a ports change (deferred, see below)
- [x] expired/prolonged voting states get the attention dot and one line of
      "what happens now" (the expired applicant dm and the extended card)

## B4. admin/moderation screens

- [x] paginated lists (applications, logs, users) go through
      `paginatedListScreen`; pagination prefixes follow `<ns>:page:<n>`
      (no admin browse list ships yet — the builder is in place and tested,
      so the first one lands on the shared layout)
- [x] admin broadcasts carry plain text only (renderPlainText) and mention
      that details live in the bot menu — no fake buttons in broadcasts
- [x] /stats output restyled with kv rows + section labels, numbers in one
      format (tabular, same units everywhere)

## B5. bot copy audit (anti-slop, whole locale files)

- [x] no greeting openers, no exclamation marks, no "Нажмите кнопку…"
      (enforced by `tests/i18n-parity.test.ts`)
- [x] no unverified claims, no filler adjectives (простой, удобный, лучший)
- [x] buttons are verbs/nouns the user controls; one primary action per
      screen, styled buttons (success/danger/primary) at most one per keyboard
- [x] errors say what happened + what to do next; nothing apologizes
- [x] empty states invite action ("Здесь пока ничего нет" + the way to fill it)
- [x] ru plural forms verified for every count-bearing string (players,
      votes, minutes, sessions)
- [x] sentence case everywhere; trailing periods only on multi-sentence bodies
      (caps-as-emphasis is rejected by the parity test)
- [x] ops notifications (services/i18n.ts strings) re-checked against the
      final event names and semantics

## B6. emoji / palette consistency (bot)

- [x] every emoji in every string comes from the registry (emojis.json) —
      in practice the only emoji left are the four status dots, and they are
      rendered by the registry, never typed into a string
- [ ] tgiosicons custom ids filled for all registry keys, BOT_CUSTOM_EMOJI
      verified on a real bot with premium owner + fallback verified without
      (needs a live bot; the code path and the premium fallback are unchanged)
- [x] dot semantics identical across screens, including a distinct "no data"
      (white) state for a missing backend link
- [x] no emoji inside button labels except the intentional state markers of
      `toggleButton` and the cancel "✕"

## B7. i18n integrity (bot)

- [x] no user-facing string hardcoded in flow code (grep for cyrillic
      literals outside src/i18n/ and services/i18n.ts)
- [x] services/i18n.ts documented as a permanent channel split (plain-text
      channel) rather than merged into the html layer — see DESIGN.md
- [x] en locale added and every key present in both locales (script check:
      `tests/i18n-parity.test.ts` compares key sets and placeholders)
- [x] missing-key errors from tests fixed at the source, not caught; dead keys
      removed with their call sites

## B8. consistency checks (bot)

- [x] all keyboards built by factories — no inline `inline_keyboard` literals
      outside keyboards/
- [x] callback data namespaces registered in one table and prefix-collision
      checked (test)
- [x] parse_mode HTML + disabled link previews on every send (composeText)
- [x] message effects used only for genuine celebration moments (approval,
      launch), ids from config not literals in flow code
- [x] ephemeral helpers used only where delivery failure is acceptable

## bot polish — deferred items

- the group vote card still prints "(МСК)" in the template instead of a
  `date_time` entity: the card travels the plain-text ports path, so entities
  would need a `sendGroupMessage`/`editMessageText` contract change and a
  per-client fallback. the timezone label lives in one dictionary entry per
  locale, so the copy is at least honest and centralised.
- the login prompt is still rendered in the ops default language: the auth
  request does not carry the user's language. the en strings exist; plumbing
  the locale through the auth module is a separate change.
- `statusScreen`'s refresh re-reads the live snapshot; it does not subscribe
  to server state pushes. a push-driven refresh needs a gateway → bot hook
  that no flow owns yet.
