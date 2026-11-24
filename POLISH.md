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
