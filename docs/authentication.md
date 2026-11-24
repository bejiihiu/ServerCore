# Authentication

Logging in is passwordless and always goes through Telegram. There is no in-game password, no `/login`, no `/register`. Every session — including reconnects after a network blip — is confirmed by the account owner with one tap.

## Why passwordless

- A Telegram account is harder to borrow than a Minecraft username.
- No password can leak from the game server because none exists.
- Confirmation is a conscious human action, so stolen session tokens alone cannot get an attacker in.
- The flow is identical for every join, which keeps the rule simple and auditable.

## Join-time checks

When a player connects, the plugin evaluates — in order:

1. Does an account exist for this UUID?
2. Is access allowed (not banned, suspended, or pending deletion)?
3. Is a Telegram account linked?
4. Is there an existing live session that needs handling?
5. Is the account otherwise blocked?
6. Does this join require confirmation?

Only when everything passes does the plugin create a session and trigger an auth request. Otherwise the player lands in Guest Mode or is refused.

## The confirmation flow

```text
player joins
   -> plugin sends PLAYER_JOINED (with session id)
   -> backend validates account status
   -> backend creates an auth request:
        - one-time token, stored as a hash
        - 60-90 second TTL
        - bound to this exact session id
        - previous pending requests for the account cancelled
   -> bot sends the confirmation card:

        SERVER AUTHENTICATION

        Player: bejiihiu
        Server: Survival S2

        A new game session was detected.

        Confirm the login.

        [Confirm login] [Reject]

   -> owner taps Confirm
   -> backend verifies telegram id, uuid, session id, expiry
   -> request status -> APPROVED
   -> AUTH_APPROVED sent to the plugin
   -> plugin validates the event, hops to the player's EntityScheduler,
      restores normal gameplay
```

Rejecting — or doing nothing until the token expires — leaves the session gated; the player remains in Guest Mode.

## Protection measures

The flow is defended in depth:

- **One-time tokens.** A confirmation proves exactly one session, once.
- **Hashed storage.** Token hashes are persisted, never the tokens themselves.
- **Short expiry.** 60–90 seconds; a stale prompt is dead on arrival.
- **Session binding.** A token confirms one specific session id — approving an old prompt cannot authenticate a new session.
- **Single-flight.** A new auth request cancels the previous pending one for the same account.
- **Rate limiting.** Auth request creation is throttled per account.
- **Callback idempotency.** Replayed confirmation callbacks cannot double-fire or resurrect an expired request.
- **Disconnect cancellation.** The player leaving cancels the pending request immediately.
- **No cross-session approvals.** The backend checks that the session being approved is the session that requested it.

## Failure posture

- **Telegram is unreachable** → logins are not auto-approved. The session stays gated until Telegram answers again. Failing closed is intentional.
- **Backend is unreachable** → same: no new approvals, no automatic grants. Already-authenticated players are not disrupted by a brief outage; guest sessions keep running locally.
- **Reconnects** → a dropped and re-established game session is a new auth request, subject to all the same checks.

## Identity model

- The Minecraft account's primary key is its **UUID** — usernames change, UUIDs do not.
- Each player has exactly one linked Telegram ID, and one Telegram account cannot serve multiple Minecraft accounts.
- Linkage is established once, through the admission flow, and verified against a live server session at that moment.
