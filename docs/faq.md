# FAQ

### Who is ServerCore for?

Closed-community Survival servers that want real admission control, identity tied to Telegram, and hardened runtime behaviour — without turning the server into a minigame hub or stuffing it with a hundred unrelated commands.

### Does it change gameplay?

No. A player who is registered and authenticated sees an ordinary Survival server. All the machinery — admission, voting, login confirmations, protection — operates around the edges.

### Why Folia only?

Folia's regionised multithreading is the platform this plugin is designed against. Schedulers like `EntityScheduler` and `RegionScheduler` are used as intended throughout, so the plugin will not run on Paper or Spigot and is not tested there.

### Do players need a password?

No. There is no password anywhere. Every session is confirmed by a single tap on a Telegram prompt, which expires within 60–90 seconds.

### What can an unregistered player do?

They spawn into Guest Mode: spectator flight, free exploration, and little else — no block interaction, no item use, no portals, and only a tiny command whitelist (`/help`, `/rules`, `/link`). Their original location is snapshotted and restored when they leave and come back. Registering happens through a personal Telegram deep link shown in chat.

### How does admission work?

The new player fills out an application in the Telegram bot. A 3-hour vote opens to existing members. Approval needs both a quorum (strictly more than 50 % of the frozen eligible-voter count) and more than 50 % of valid votes in favour. Approved players get a single-use invite to the private Telegram group; only after the join is confirmed does the account become fully active. A rejected player can reapply after 3 months.

### What happens if the backend goes down?

Nothing dangerous. New logins are refused — never auto-approved — but already-authenticated players keep playing, guest sessions keep working, and events generated during the outage queue in a durable outbox and replay on reconnect.

### What happens if Telegram is down?

Logins stay pending. Failing closed is deliberate: a session is not authenticated just because nobody could answer the prompt.

### How does anti-ESP actually work?

For every viewer/target pair the server decides one of three states: visible, disguised, or hidden. Hidden players are replaced with a per-viewer synthetic entity (a random mob) with synthetic equipment and attributes, or removed entirely. Line-of-sight is decided server-side from block geometry, distance and sneaking state. Results are cached and re-evaluated at a controlled rate. It is not an invisibility cloak — sounds, particles and block interactions still betray presence.

### Can staff watch a player without being seen?

Yes. `/spec <player>` moves an admin into an undetectable spectator session with a live read-only view of the target's inventory. Every session is audit-logged, the admin's own state is restored on exit, and the session ends automatically if either side disconnects.

### Is OP needed to administer the server?

No — and deliberately not granted. Day-to-day permissions live in LuckPerms with minimal grants. A long list of world- and server-altering commands is blocked for all game accounts regardless of OP status.

### Why isn't moderation part of ServerCore?

Because [FlectonePulse](https://github.com/FlectonePulse/FlectonePulse) already does chat and moderation well. ServerCore deliberately does not build its own `/ban`, `/mute`, prefixes or chat pipeline — it integrates instead of duplicating.

### Is it a general-purpose "core" plugin?

No. It is purpose-built infrastructure for one specific kind of community server. If you need a multi-server network suite or a features-everywhere core, this is not it.

### Where do I report a security issue?

Privately, per [SECURITY.md](../SECURITY.md). Please do not open public issues for vulnerabilities.
