import { parseStartPayload } from "./start-payload.js"
import type { Bot, Context } from "grammy"

/**
 * /start — entry point of the whole flow.
 * with a valid deep-link token: confirms the token format and tells the user
 * the linking flow will continue. without: explains how to get a personal link.
 * /start never creates an account by itself.
 */
export async function handleStartCommand(ctx: Context): Promise<void> {
  const match = ctx.match ?? ""
  const result = parseStartPayload(match)

  if (!result.ok) {
    await ctx.reply(
      result.reason === "MISSING"
        ? [
            "Привет! Это бот доступа на сервер.",
            "",
            "Персональную ссылку для регистрации бот выдаёт в игре — зайди на сервер,",
            "и в игровом чате появится твоя ссылка для запуска регистрации.",
          ].join("\n")
        : [
            "Ссылка недействительна: код привязки повреждён или устарел.",
            "",
            "Зайди на сервер заново — в игровом чате появится свежая персональная ссылка.",
          ].join("\n"),
    )
    return
  }

  // real linking (token redemption against the store) arrives with the
  // account module; format is already verified here
  await ctx.reply(
    [
      "Код привязки принят.",
      "",
      "Проверяю, что этот код выдан именно вам... Продолжение привязки аккаунта появится в следующем шаге.",
    ].join("\n"),
  )
}

export function registerStartCommand(bot: Bot): void {
  bot.command("start", (ctx) => handleStartCommand(ctx))
}
