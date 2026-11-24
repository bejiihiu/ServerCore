import type { Context, Bot } from "grammy"
import type { StatsService } from "../../services/runtime-stats.js"
import { t, type Locale } from "../../services/i18n.js"

/**
 * /stats - admin-only ops dashboard: server presence from the last heartbeat,
 * websocket state, session counters, backend uptime. every number comes from
 * the live in-memory state, nothing is synthesized.
 */
export function registerStatsCommand<C extends Context>(
  bot: Bot<C>,
  deps: { stats: StatsService; adminIds: readonly number[]; locale: Locale },
): void {
  if (deps.adminIds.length === 0) return

  bot.command("stats", (ctx) => {
    const fromId = ctx.from?.id
    if (fromId === undefined || !deps.adminIds.includes(fromId)) return
    return replyWithStats(ctx, deps.stats, deps.locale)
  })
}

async function replyWithStats(ctx: Context, stats: StatsService, locale: Locale): Promise<void> {
  const snap = stats.snapshot()
  const lines: string[] = []

  lines.push(t(locale, "ops.stats.uptime", { uptime: formatDuration(snap.uptimeSeconds) }))
  lines.push(t(locale, "ops.stats.ws", {
    state: snap.wsRunning ? t(locale, "ops.stats.ws_up") : t(locale, "ops.stats.ws_down"),
    connections: snap.wsConnections,
  }))
  lines.push(t(locale, "ops.stats.sessions", {
    active: snap.counters.activeSessions,
    guests: snap.counters.guestSessions,
    pending: snap.counters.pendingAuths,
  }))

  if (snap.servers.length === 0) {
    lines.push(t(locale, "ops.stats.no_servers"))
  } else {
    for (const server of snap.servers) {
      lines.push(t(locale, "ops.stats.server", {
        server: server.serverId,
        state: server.state ?? t(locale, "ops.stats.state_unknown"),
        players: `${server.onlinePlayers}/${server.maxPlayers}`,
        seen: server.secondsSinceLastSeen,
      }))
    }
  }

  await ctx.reply(lines.join("\n"))
}

function formatDuration(totalSeconds: number): string {
  const days = Math.floor(totalSeconds / 86_400)
  const hours = Math.floor((totalSeconds % 86_400) / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const parts: string[] = []
  if (days > 0) parts.push(`${days}d`)
  if (hours > 0) parts.push(`${hours}h`)
  parts.push(`${minutes}m`)
  return parts.join(" ")
}
