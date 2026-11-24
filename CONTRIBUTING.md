# Contributing

Thanks for your interest in ServerCore. This is infrastructure for a specific closed community, but well-made fixes and improvements are welcome.

## Ground rules

- **Read the docs first.** The [architecture](docs/architecture.md) and [security](docs/security.md) pages explain decisions that are easy to accidentally undo.
- **Folia only.** The plugin targets Folia's regionised threading model. No Bukkit scheduler, no assumptions about a main thread. If your change touches scheduling, use the right scheduler for the resource involved.
- **Stack discipline.** Plugin side: Kotlin on Java 25. Backend side: Bun + TypeScript, strict typing, Zod validation at every boundary. No Node.js runtime, no `any` without justification.
- **One transport.** Plugin ↔ backend traffic goes through the WebSocket channel only. Do not introduce HTTP calls between them.
- **No stubs.** Changes ship complete — no TODO markers, mock implementations, or silent fallbacks in place of real behaviour.
- **Fail closed.** Any change to authentication or access control must preserve the rule: when uncertain or unreachable, deny — never default to allow.
- **Least privilege.** New capabilities require new, narrow permissions rather than piggybacking on broad ones.

## Workflow

1. Fork or create a feature branch from `main`.
2. Keep changes focused — one logical change per PR.
3. Add or update tests for business logic on the backend and for critical plugin paths.
4. Run the build and tests locally before pushing.
5. Open a PR using [the template](.github/pull_request_template.md).

## Commit style

We use [Conventional Commits](https://www.conventionalcommits.org/) with subjects of 50 characters or fewer, for example:

- `feat: add quorum evaluation to votes`
- `fix: restore guest snapshot after restart`
- `docs: expand websocket reconnect section`

## Review

Every PR is reviewed for correctness, Folia thread-safety, and security impact. Changes that weaken fail-closed behaviour or widen privileges will be asked to justify themselves — or be declined.

## Reporting problems

- Bugs → [bug report template](.github/ISSUE_TEMPLATE/bug_report.md)
- Ideas → [feature request template](.github/ISSUE_TEMPLATE/feature_request.md)
- Security vulnerabilities → **never** a public issue; see [SECURITY.md](SECURITY.md)
