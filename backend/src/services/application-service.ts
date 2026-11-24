import type {
  ApplicationDraftRepository,
  ApplicationInfo,
  ApplicationRepository,
  AuditSink,
  DraftInfo,
  Lang,
  TelegramApi,
  UserInfo,
  UserRepository,
  VoterInfo,
} from "./ports.js"
import type { AdmissionNotifier } from "./admission-notifier.js"
import { t } from "./i18n.js"
import { formatMoscowTime } from "./moscow-time.js"
import { isDraftComplete, validateAnswer } from "./questionnaire.js"
import type { RateLimiter } from "./rate-limiter.js"
import type { Clock } from "./ports.js"

export type StartBlockReason =
  | "NO_USER"
  | "NOT_LINKED"
  | "INELIGIBLE_STATUS"
  | "LIVE_APPLICATION"
  | "COOLDOWN"
  | "RATE_LIMITED"

export type StartDraftOutcome =
  | { ok: true; draft: DraftInfo; resumed: boolean }
  | { ok: false; reason: StartBlockReason; cooldownUntil?: Date }

export type SubmitBlockReason = StartBlockReason | "NO_DRAFT" | "INCOMPLETE"

export type SubmitOutcome =
  | { ok: true; application: ApplicationInfo; voters: VoterInfo[] }
  | { ok: false; reason: SubmitBlockReason }

export type GetApplicationOutcome =
  | { ok: true; application: ApplicationInfo }
  | { ok: false; reason: "NO_USER" | "NO_APPLICATION" }

/**
 * questionnaire + application lifecycle.
 *
 * the draft lives in its own collection so filling survives bot restarts;
 * the 3-month cooldown rides on user.lastApplicationAt, so it holds even
 * when a previous application document was deleted.
 */
export class ApplicationService {
  constructor(
    private readonly deps: {
      users: UserRepository
      applications: ApplicationRepository
      drafts: ApplicationDraftRepository
      audit: AuditSink
      telegram: TelegramApi
      notifier: AdmissionNotifier
      rateLimiter: RateLimiter
      clock: Clock
      /** how long after a submit a new application is blocked (3 months) */
      cooldownMs: number
      /** voting window length, fixed at submit time */
      votingDurationMs: number
      /** configured question lists per language, equal length enforced in config */
      questions: { ru: string[]; en: string[] }
      /** language for group/admin-facing texts */
      defaultLanguage: Lang
    },
  ) {}

  /** question count drives draft completeness; ru/en lengths match per config */
  get questionCount(): number {
    return this.deps.questions.ru.length
  }

  get questionsRu(): string[] {
    return this.deps.questions.ru
  }

  get questionsEn(): string[] {
    return this.deps.questions.en
  }

  /** draft lookup for the conversation builder (via external) */
  findDraftForConversation(telegramId: string): Promise<DraftInfo | null> {
    return this.deps.drafts.findByTelegramId(telegramId)
  }

  questionText(lang: Lang, index: number): string | null {
    const list = lang === "en" ? this.deps.questions.en : this.deps.questions.ru
    return list[index] ?? null
  }

  /** shared eligibility gate for starting and submitting */
  private async checkEligibility(
    telegramId: string,
  ): Promise<{ ok: true; user: UserInfo } | { ok: false; reason: StartBlockReason; cooldownUntil?: Date }> {
    const user = await this.deps.users.findByTelegramId(telegramId)
    if (!user) return { ok: false, reason: "NO_USER" }
    if (user.telegramId.length === 0) return { ok: false, reason: "NOT_LINKED" }
    // only accounts not yet in the admission flow can apply; banned and
    // active accounts land here too
    if (user.status !== "UNREGISTERED") return { ok: false, reason: "INELIGIBLE_STATUS" }
    const live = await this.deps.applications.findLiveByUuid(user.uuid)
    if (live) return { ok: false, reason: "LIVE_APPLICATION" }
    if (this.coolingDownUntil(user.lastApplicationAt)) {
      return {
        ok: false,
        reason: "COOLDOWN",
        cooldownUntil: new Date(user.lastApplicationAt!.getTime() + this.deps.cooldownMs),
      }
    }
    return { ok: true, user }
  }

  /** null when no cooldown applies, otherwise the exact unblock date */
  private coolingDownUntil(lastApplicationAt: Date | null): Date | null {
    if (!lastApplicationAt) return null
    const until = new Date(lastApplicationAt.getTime() + this.deps.cooldownMs)
    return this.deps.clock.nowMs() < until.getTime() ? until : null
  }

  /**
   * starts (or resumes) a questionnaire draft. rate limiting only counts
   * fresh starts, resuming an existing draft is always free.
   */
  async startDraft(telegramId: string, language?: Lang): Promise<StartDraftOutcome> {
    const check = await this.checkEligibility(telegramId)
    if (!check.ok) return check

    const existing = await this.deps.drafts.findByTelegramId(telegramId)
    if (existing) return { ok: true, draft: existing, resumed: true }

    if (!this.deps.rateLimiter.tryAcquire(telegramId)) {
      return { ok: false, reason: "RATE_LIMITED" }
    }

    const draft: DraftInfo = {
      telegramId,
      minecraftUuid: check.user.uuid,
      username: check.user.username,
      step: 0,
      answers: [],
      language: language ?? check.user.language,
    }
    await this.deps.drafts.save(draft)
    await this.deps.audit.record({
      actorType: "PLAYER",
      actorId: check.user.uuid,
      action: "application.draft_started",
      targetType: "user",
      targetId: check.user.uuid,
    })
    return { ok: true, draft, resumed: false }
  }

  /** validates and persists one answered question into the durable draft */
  async saveDraftStep(
    telegramId: string,
    step: number,
    rawAnswer: string,
  ): Promise<{ ok: true } | { ok: false; reason: "NO_DRAFT" | "EMPTY" | "TOO_LONG" }> {
    const draft = await this.deps.drafts.findByTelegramId(telegramId)
    if (!draft) return { ok: false, reason: "NO_DRAFT" }
    const validation = validateAnswer(rawAnswer)
    if (!validation.ok) return validation
    const answers = [...draft.answers]
    answers[step] = validation.value
    await this.deps.drafts.save({ ...draft, step: Math.max(draft.step, step + 1), answers })
    return { ok: true }
  }

  /** drops the draft, the user changed their mind */
  async cancelDraft(telegramId: string): Promise<boolean> {
    const draft = await this.deps.drafts.deleteByTelegramId(telegramId)
    if (!draft) return false
    await this.deps.audit.record({
      actorType: "PLAYER",
      actorId: draft.minecraftUuid,
      action: "application.cancelled",
      targetType: "user",
      targetId: draft.minecraftUuid,
    })
    return true
  }

  /**
   * submits the questionnaire and opens the voting. the draft delete is the
   * single-winner gate: a second concurrent submit finds no draft anymore.
   */
  async submit(telegramId: string): Promise<SubmitOutcome> {
    const draft = await this.deps.drafts.findByTelegramId(telegramId)
    if (!draft) return { ok: false, reason: "NO_DRAFT" }
    if (!isDraftComplete(draft.answers, this.questionCount)) {
      return { ok: false, reason: "INCOMPLETE" }
    }

    const check = await this.checkEligibility(telegramId)
    if (!check.ok) return check

    // claim the draft atomically: only one submit can delete it
    const claimed = await this.deps.drafts.deleteByTelegramId(telegramId)
    if (!claimed) return { ok: false, reason: "NO_DRAFT" }

    const now = new Date(this.deps.clock.nowMs())
    const voters = await this.deps.users.listEligibleVoters()

    // guarded transition first: UNREGISTERED -> VOTING. fails when the user
    // slipped into another state meanwhile
    const transitioned = await this.deps.users.setStatus(check.user.uuid, ["UNREGISTERED"], "VOTING")
    if (!transitioned) return { ok: false, reason: "INELIGIBLE_STATUS" }

    // freeze the eligible voter count at voting start; the voter pool may
    // change freely afterwards without affecting this voting. questions are
    // stored in the default language for audit, rendering is per-viewer
    const questions = this.deps.questions[this.deps.defaultLanguage]
    const inserted = await this.deps.applications.insert({
      minecraftUuid: check.user.uuid,
      telegramId,
      answers: draft.answers.map((answer, i) => ({
        question: questions[i] ?? "",
        answer,
      })),
      voting: {
        startedAt: now,
        expiresAt: new Date(now.getTime() + this.deps.votingDurationMs),
        eligibleVoters: voters.length,
      },
    })

    if (inserted === "duplicate-live") {
      // lost a race against another live application, put the status back
      await this.deps.users.setStatus(check.user.uuid, ["VOTING"], "UNREGISTERED")
      return { ok: false, reason: "LIVE_APPLICATION" }
    }

    await this.deps.users.setLastApplicationAt(check.user.uuid, now)
    await this.deps.audit.record({
      actorType: "PLAYER",
      actorId: check.user.uuid,
      action: "application.submitted",
      targetType: "application",
      targetId: inserted.id,
      details: { eligibleVoters: voters.length },
    })

    const application = await this.deps.applications.findById(inserted.id)
    if (!application) return { ok: false, reason: "NO_DRAFT" }

    return { ok: true, application, voters }
  }

  /** shows the latest submitted application of this telegram account */
  async getSubmittedApplication(telegramId: string): Promise<GetApplicationOutcome> {
    const user = await this.deps.users.findByTelegramId(telegramId)
    if (!user) return { ok: false, reason: "NO_USER" }
    const application = await this.deps.applications.findLatestByUuid(user.uuid)
    if (!application) return { ok: false, reason: "NO_APPLICATION" }
    return { ok: true, application }
  }

  /** localized explanation for a blocked start/submit */
  blockText(
    reason: StartBlockReason | "NO_DRAFT" | "INCOMPLETE",
    lang: Lang,
    cooldownUntil?: Date,
  ): string {
    switch (reason) {
      case "NO_USER":
        return t(lang, "status.notFound")
      case "NOT_LINKED":
        return t(lang, "status.notLinked")
      case "RATE_LIMITED":
        return t(lang, "app.rateLimited")
      case "LIVE_APPLICATION":
        return t(lang, "app.blockedLive")
      case "INELIGIBLE_STATUS":
        return t(lang, "app.blockedStatus")
      case "COOLDOWN":
        return t(lang, "app.blockedCooldown", {
          date: cooldownUntil
            ? formatMoscowTime(cooldownUntil)
            : formatMoscowTime(new Date(this.deps.clock.nowMs() + this.deps.cooldownMs)),
        })
      case "NO_DRAFT":
      case "INCOMPLETE":
        return t(lang, "error.generic")
    }
  }
}
