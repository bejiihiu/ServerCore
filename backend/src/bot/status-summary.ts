/**
 * turns a live stats snapshot into the one line the status screen shows.
 *
 * the distinction that matters for the user: a backend with a working gateway
 * and no connected server means the server is down (red), while a backend
 * without a gateway means we simply cannot tell (white). guessing red there
 * would blame the server for a missing link on our side.
 */

export type ServerSummaryInput = {
  wsRunning: boolean
  servers: ReadonlyArray<{ state?: string | null; onlinePlayers: number }>
}

export type ServerSummary = { online: boolean | null; players: number }

export function summarizeServer(snapshot: ServerSummaryInput | null): ServerSummary {
  if (snapshot === null) return { online: null, players: 0 }
  const players = snapshot.servers.reduce((sum, server) => sum + server.onlinePlayers, 0)
  if (snapshot.servers.length === 0) {
    return snapshot.wsRunning ? { online: false, players: 0 } : { online: null, players: 0 }
  }
  return { online: snapshot.servers.some((server) => server.state === "RUNNING"), players }
}
