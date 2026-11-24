# Security

Security is a design constraint, not a retrofit. This page documents the threat model, the token handling rules, and the approach to command protection.

## Threat model

| Threat | Mitigation |
|---|---|
| Cheat clients (ESP, x-ray style overlays) | Per-viewer entity visibility: hidden or disguised representations, synthetic attributes, server-side line-of-sight authority |
| Stolen Minecraft session / borrowed account | Every login requires a live Telegram confirmation; no in-game secret exists to steal |
| Multiple Telegram accounts per player | One Telegram ID ↔ one UUID; duplicate linkage is rejected |
| Vote manipulation | One vote per UUID, frozen voter roll, own-application veto, persistence of every ballot |
| Replay of WebSocket messages | Timestamp windows + nonce tracking + HMAC signatures |
| Forged plugin/backend messages | Per-server HMAC secret; unsigned or stale messages are dropped |
| Privilege escalation in game | OP never used; permission checks through LuckPerms; dangerous commands blocked for all game accounts |
| Gamemode abuse | Non-sanctioned gamemode changes reverted to Survival; exceptions derived from ServerCore's internal state, not from OP status |
| Secret leakage | No secrets in the repository or logs; environment-only configuration; placeholder values in all examples |
| Data loss on crash/restart | Persistent state in MongoDB; durable outbox for unsent events; snapshots survive restarts |
| Silent takeover during outages | Fail closed: no backend, no approvals |

## Token handling

- All tokens are **one-time use** and bound to exactly one session.
- Only **hashes** are stored (`auth_requests.tokenHash`); plaintext tokens never touch the database or logs.
- Login tokens expire in **60–90 seconds**; an expired request cannot be resurrected by a replayed callback.
- Transient records use **TTL indexes** in MongoDB so expired artifacts disappear without manual cleanup. Core records — accounts, votes, audit — never TTL-expire.
- Linking codes in deep links are single-use as well; a used code is dead.
- Logs contain no secrets, no tokens, no full personal data.

## WebSocket channel security

- WSS/TLS mandatory; the plugin is the connecting side.
- Handshake verifies the server identity with an HMAC over the hello frame (timestamp + nonce included).
- Critical messages are individually signed.
- Nonces are tracked; duplicate nonces and stale timestamps are rejected — replay is not an option.
- Every message body passes runtime schema validation on the receiving side; malformed frames are dropped, not interpreted.
- Frame size is capped; oversized input is rejected before parsing.

## Command protection approach

The goal: no game account, including an operator-flagged one, can alter server or world state through administrative commands.

- **Baseline blacklist** applied to every game account: `/op`, `/deop`, `/fill`, `/clone`, `/setblock`, `/summon`, `/gamemode`, `/tp`, `/teleport`, `/give`, `/item`, `/execute`, `/effect`, `/attribute`, `/kill`, `/difficulty`, `/time`, `/weather`, `/gamerule`, `/worldborder`, `/stop`, `/reload`.
- **Aliases and namespaces blocked too** — blocking the literal command name is not enough; alternative execution paths are enumerated and covered.
- **Alternative sources audited:** plugin-registered commands, command blocks, datapack functions, RCON, the console, and calls from other plugins' APIs. The console is the one trusted administrative channel.
- **Gamemode enforcement:** Survival is the only player-facing mode. Creative, Adventure or Spectator outside sanctioned scenarios (Guest Mode, `/spec`, explicitly permitted system states) is reverted. Sanctioned states are tracked by ServerCore's internal state machine — the mere presence of OP decides nothing.
- **PlayerCommandPreprocessEvent alone is not trusted** as protection — a command may be invoked through other plugins or mechanisms. Blocking is layered at the command source level.

## Privileges

- OP is not an administration mechanism; game accounts carry no operator status.
- Permissions come from LuckPerms with minimal grants: `servercore.admin`, `servercore.spec`, `servercore.spec.other`, `servercore.guest.manage`, `servercore.account.manage`.
- Every administrative operation — especially observation sessions and account deletions — is written to `audit_logs`, with access to that log restricted.
- Hidden observation is invisible to the observed player but **never invisible to the audit trail**.

## Anti-ESP honesty

Entity-level disguise does not promise absolute invisibility:

- Sounds, particles, block changes, door and container openings leak presence.
- Third-party clients may infer more than the vanilla client does.
- The system raises the cost of wall-hacking substantially; it does not claim impossibility.

## Safe deletion

Account deletion is a sequence, not a delete key:

1. Identity is re-verified in Telegram.
2. The UUID is locked against new sessions.
3. The active session is terminated.
4. The member is removed from the Telegram group; unused invites revoked.
5. The Telegram ↔ Minecraft link is revoked.
6. Personal data is deleted or anonymized per the retention policy.
7. A de-identified audit record is written.

The account record is not removed until every access-revocation step has completed.

## Reporting

Found a vulnerability? Do not open a public issue. Follow [SECURITY.md](../SECURITY.md) for private disclosure.
