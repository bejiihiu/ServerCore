/**
 * timezone helpers. the project standard is Europe/Moscow for logs and
 * schedules; everything goes through here so the zone is explicit, never the
 * host default.
 */

const PARTS_CACHE = new Map<string, Intl.DateTimeFormat>()

function zonedFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = PARTS_CACHE.get(timeZone)
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat("en-CA", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
      hourCycle: "h23",
    })
    PARTS_CACHE.set(timeZone, formatter)
  }
  return formatter
}

function partsToMap(date: Date, timeZone: string): Map<string, string> {
  const map = new Map<string, string>()
  for (const part of zonedFormatter(timeZone).formatToParts(date)) {
    map.set(part.type, part.value)
  }
  return map
}

/**
 * iso string with the zone's wall-clock time and its numeric offset, e.g.
 * "2026-11-24T14:30:05+03:00". the offset is derived from the zone itself,
 * so dst zones stay correct too.
 */
export function zonedIso(date: Date, timeZone: string): string {
  const parts = partsToMap(date, timeZone)
  const year = parts.get("year") ?? "1970"
  const month = parts.get("month") ?? "01"
  const day = parts.get("day") ?? "01"
  const hour = parts.get("hour") === "24" ? "00" : (parts.get("hour") ?? "00")
  const minute = parts.get("minute") ?? "00"
  const second = parts.get("second") ?? "00"

  // wall clock read back as if it were utc gives the offset
  const asUtc = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second))
  const offsetMinutes = Math.round((asUtc - date.getTime()) / 60_000)
  const sign = offsetMinutes < 0 ? "-" : "+"
  const absOffset = Math.abs(offsetMinutes)
  const offsetHours = String(Math.floor(absOffset / 60)).padStart(2, "0")
  const offsetRest = String(absOffset % 60).padStart(2, "0")

  return `${year}-${month}-${day}T${hour}:${minute}:${second}${sign}${offsetHours}:${offsetRest}`
}

/** pino timestamp function rendering time in the configured zone */
export function pinoTimestampFor(timeZone: string): () => string {
  return () => `,"time":"${zonedIso(new Date(), timeZone)}"`
}
