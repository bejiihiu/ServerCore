# Security policy

## Supported versions

Only the latest release receives security fixes. Update before reporting.

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

Report privately instead:

1. Open a private security advisory via GitHub's *Security* tab → *Report a vulnerability*, or
2. Contact the maintainer directly through GitHub (@bejiihiu).

Please include:

- A description of the issue and its impact
- Steps to reproduce (proof of concept is welcome)
- Affected versions or commits
- Any suggested fix, if you have one

## What to expect

- **Acknowledgement** within 72 hours
- **Assessment** and a severity rating shortly after
- A fix coordinated with you, with credit if you want it
- Public disclosure only after the fix ships, and only in agreement with you

## Rules for reporters

- Give us a reasonable window to fix the issue before any public disclosure.
- Do not test against live infrastructure beyond what is needed to demonstrate the issue.
- Do not access, modify or exfiltrate data that is not yours.

## Scope notes

ServerCore's sensitive surfaces: the WebSocket channel between plugin and backend, the Telegram bot's authorization flows, one-time tokens and session binding, and command/privilege enforcement inside the game server. Reports about those areas are especially valued. Anti-ESP is explicitly documented as a mitigation that raises cost, not an absolute guarantee — reports noting its known limits are still appreciated, but behaviour covered in [docs/security.md](docs/security.md) is not considered a vulnerability by itself.
