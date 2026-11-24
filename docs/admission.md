# Admission

Admission is how a stranger becomes a member. Everything happens in Telegram; the game server only gates the result. The pipeline has five stages: linking, application, voting, decision, and group membership.

## Stage 1 — First contact and linking

A first-time join puts the player into Guest Mode (spectator flight, restricted interactions) and posts a personal deep link in chat:

```text
To gain access to the server you need to
register through Telegram.

Telegram: t.me/ServerCoreBot?start=<one-time-code>
```

The link carries a **one-time linking code**, never a raw Telegram ID or the player's UUID in a guessable form.

After the player opens the bot:

1. The backend validates the one-time code.
2. The player confirms the Telegram linkage in the bot.
3. The backend verifies that the Minecraft UUID actually belongs to this person — by checking against the **live server session**, not just the typed username.
4. A preliminary Telegram ↔ UUID link is created.
5. The application form unlocks.

A bare `/start` without a valid code opens the bot interface but creates no Minecraft account.

## Stage 2 — Application

The application is a step-by-step Telegram conversation: one question per message, inline-keyboard navigation, cancellable at any step, with intermediate progress saved.

Typical questions:

1. How long have you been playing Minecraft?
2. Why do you want to join?
3. How often do you plan to play?
4. Do you have experience with servers like this?

Guarantees:

- The linked UUID is attached automatically — the player cannot misattribute the application.
- Repeated submissions are rate-limited; a duplicate cannot overwrite a pending one.
- The applicant can review the submitted form afterwards.
- **One application per UUID per 3 months.** The cooldown is anchored to `lastApplicationAt` and survives application deletion — deleting an old form does not reset the clock.
- On rejection the applicant receives the result in the bot.

## Stage 3 — Voting

Submission starts a community vote that runs for **3 hours**. Eligible voters are current members with an active account; the voter count is **frozen when the vote starts**, so later joins or leaves cannot change the bar an in-flight vote must clear.

Members receive a card like:

```text
NEW APPLICATION

Player: bejiihiu

Reason for joining:
...

Voting ends in 3 hours.

[Accept (7)] [Reject (3)]
```

Vote integrity rules:

- **One UUID, one vote** per application — enforced by a unique compound index on (application, voter).
- You cannot vote on your own application.
- You cannot vote without an active account.
- Multiple Telegram accounts linked to one UUID do not multiply votes.
- Repeated or stale callback presses never change an already-recorded vote.
- Every vote is persisted in MongoDB as its own record.

## Stage 4 — Decision

Two separate conditions, both required:

- **Quorum:** at least **50 %** of eligible voters (as frozen at start) cast a vote.
- **Result:** strictly more than **50 %** of valid votes are in favour.

Worked example: 20 eligible members, quorum threshold 10. Twelve votes arrive, 8 for and 4 against. Quorum is met and 8 > 50 % of 12 valid votes — approved.

If the window expires without quorum, the application is marked `EXPIRED` and routed to separate handling — an unfinished vote is **never** silently counted as approval.

## Stage 5 — Group membership

Approval is not the finish line; membership is confirmed by an actual join:

1. The application transitions to `APPROVED`.
2. The backend generates a **single-use** invite link.
3. The applicant receives the link in the bot.
4. When the player joins the group, the bot receives the member event.
5. The backend matches the new member to the approved account and confirms the association.
6. Once every activation condition holds, the account status becomes `ACTIVE`.

Sending the invite proves nothing by itself — the confirmed join does.

## Aftercare

- **Reapplication:** after a rejection or expiry the UUID is locked out of new applications for 3 months.
- **Account deletion:** removing an account revokes Minecraft access, cancels pending auth requests, removes the member from the Telegram group via the Bot API, revokes unused invites, and writes an anonymized audit record.
- **Audit:** every stage transition — submission, vote start, each vote, decision, invite issuance, group confirmation — is recorded.
