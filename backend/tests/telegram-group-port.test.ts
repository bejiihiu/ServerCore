import { describe, expect, test } from "bun:test"
import { BotApiTelegramGroupPort } from "../src/modules/account/deletion.js"
import { AccountError } from "../src/modules/account/types.js"

type Call = string

function fakeApi() {
  const calls: Call[] = []
  return {
    calls,
    api: {
      async banChatMember(chatId: string | number, userId: number): Promise<unknown> {
        calls.push(`ban:${chatId}:${userId}`)
        return true
      },
      async unbanChatMember(
        chatId: string | number,
        userId: number,
        options?: { only_if_banned?: boolean },
      ): Promise<unknown> {
        calls.push(`unban:${chatId}:${userId}:${options?.only_if_banned === true}`)
        return true
      },
      async revokeChatInviteLink(chatId: string | number, link: string): Promise<unknown> {
        calls.push(`revoke:${chatId}:${link}`)
        return true
      },
    },
  }
}

describe("bot api group port", () => {
  test("kick is the ban + immediate unban pair, in that order", async () => {
    const { api, calls } = fakeApi()
    const port = new BotApiTelegramGroupPort(api, "-100500")

    await port.kickFromGroup("42")

    expect(calls).toEqual(["ban:-100500:42", "unban:-100500:42:true"])
  })

  test("a non-numeric telegram id fails closed before any api call", async () => {
    const { api, calls } = fakeApi()
    const port = new BotApiTelegramGroupPort(api, "-100500")

    const error = await port.kickFromGroup("not-a-number").catch((err: unknown) => err)

    expect(error).toBeInstanceOf(AccountError)
    expect((error as AccountError).code).toBe("TELEGRAM_GROUP_UNAVAILABLE")
    expect(calls).toEqual([])
  })

  test("an unconfigured group chat fails closed instead of deleting silently", async () => {
    const { api, calls } = fakeApi()
    const port = new BotApiTelegramGroupPort(api, "")

    const error = await port.kickFromGroup("42").catch((err: unknown) => err)

    expect(error).toBeInstanceOf(AccountError)
    expect((error as AccountError).code).toBe("TELEGRAM_GROUP_UNAVAILABLE")
    expect(calls).toEqual([])
  })

  test("a telegram api rejection propagates so deletion aborts", async () => {
    const { api } = fakeApi()
    api.banChatMember = async () => {
      throw new Error("bot lacks admin rights")
    }
    const port = new BotApiTelegramGroupPort(api, "-100500")

    await expect(port.kickFromGroup("42")).rejects.toThrow("bot lacks admin rights")
  })
})
