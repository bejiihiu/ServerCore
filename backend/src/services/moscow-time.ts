/**
 * schedules and human-readable timestamps use Europe/Moscow everywhere,
 * per the project decisions. Intl handles the utc+3 offset incl. dst rules.
 */
const mskFormat = new Intl.DateTimeFormat("ru-RU", {
  timeZone: "Europe/Moscow",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
})

export function formatMoscowTime(date: Date): string {
  return mskFormat.format(date)
}
