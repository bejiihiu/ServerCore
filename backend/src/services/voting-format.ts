import type { AnswerPair, ApplicationInfo, InlineKeyboard, Lang } from "./ports.js"
import { t, statusLabel } from "./i18n.js"
import { formatMoscowTime } from "./moscow-time.js"

/** callback data layout for voting buttons */
export function voteCallbackData(applicationId: string, decision: "ACCEPT" | "REJECT"): string {
  return `vote:${applicationId}:${decision}`
}

export function parseVoteCallbackData(
  data: string,
): { applicationId: string; decision: "ACCEPT" | "REJECT" } | null {
  const match = /^vote:([a-f0-9]{24}):(ACCEPT|REJECT)$/.exec(data)
  if (!match) return null
  return { applicationId: match[1] ?? "", decision: match[2] as "ACCEPT" | "REJECT" }
}

/**
 * [Принять (n)] [Отклонить (m)] with live counts. accept is styled success,
 * reject danger (bot api 9.4); optional custom emoji icons when the bot
 * owner has premium (config flag).
 */
export function voteKeyboard(
  lang: Lang,
  applicationId: string,
  counts: { votesFor: number; votesAgainst: number },
  icons?: { acceptId: string | null; rejectId: string | null },
): InlineKeyboard {
  const accept: InlineKeyboard[number][number] = {
    text: t(lang, "vote.buttonAccept", { n: counts.votesFor }),
    data: voteCallbackData(applicationId, "ACCEPT"),
    style: "success",
  }
  const reject: InlineKeyboard[number][number] = {
    text: t(lang, "vote.buttonReject", { n: counts.votesAgainst }),
    data: voteCallbackData(applicationId, "REJECT"),
    style: "danger",
  }
  if (icons?.acceptId) accept.iconCustomEmojiId = icons.acceptId
  if (icons?.rejectId) reject.iconCustomEmojiId = icons.rejectId
  return [[accept, reject]]
}

export function formatAnswersList(answers: AnswerPair[]): string {
  return answers
    .map((pair, i) => `${i + 1}. ${pair.question}\n${pair.answer}`)
    .join("\n\n")
}

/** localized group announcement with the end time in moscow time */
export function formatAnnouncement(input: {
  lang: Lang
  username: string
  answers: AnswerPair[]
  expiresAt: Date
}): string {
  return t(input.lang, "vote.announce", {
    username: input.username,
    answers: formatAnswersList(input.answers),
    endTime: formatMoscowTime(input.expiresAt),
  })
}

/** localized per-viewer application view; questions re-rendered by index */
export function formatApplicationView(
  application: ApplicationInfo,
  lang: Lang,
  questions: string[],
): string {
  const rendered = application.answers
    .map((pair, i) => `${i + 1}. ${questions[i] ?? pair.question}\n${pair.answer}`)
    .join("\n\n")
  const body = t(lang, "app.summary", { username: application.minecraftUuid, answers: rendered })
  return `${t(lang, "app.viewHeader")}\n\n${body}\n\n${t(lang, "vote.resultLine", {
    verdict: statusLabel(application.status, lang),
  })}`
}

/** the one-line verdict appended to the group message when voting closes */
export function formatResultLine(
  lang: Lang,
  decision: {
    status: "APPROVED" | "REJECTED" | "EXPIRED"
    votesFor: number
    votesAgainst: number
    votesCast: number
    eligibleVoters: number
  },
): string {
  if (decision.status === "EXPIRED") {
    return t(lang, "vote.resultLine", {
      verdict: t(lang, "vote.resultExpired", {
        cast: decision.votesCast,
        eligible: decision.eligibleVoters,
      }),
    })
  }
  const key = decision.status === "APPROVED" ? "vote.resultApproved" : "vote.resultRejected"
  return t(lang, "vote.resultLine", {
    verdict: t(lang, key, { for: decision.votesFor, against: decision.votesAgainst }),
  })
}
