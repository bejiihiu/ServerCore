/**
 * questionnaire mechanics. the questions themselves live in the backend
 * config (ru/en lists of equal length); this module only knows the rules:
 * answer shape, draft completeness.
 */
export const ANSWER_MAX_LENGTH = 1000

export type AnswerValidation = { ok: true; value: string } | { ok: false; reason: "EMPTY" | "TOO_LONG" }

/** trims and validates a single questionnaire answer */
export function validateAnswer(raw: string): AnswerValidation {
  const value = raw.trim()
  if (value.length === 0) return { ok: false, reason: "EMPTY" }
  if (value.length > ANSWER_MAX_LENGTH) return { ok: false, reason: "TOO_LONG" }
  return { ok: true, value }
}

/** a draft is submittable when every configured question has an answer */
export function isDraftComplete(answers: string[], questionCount: number): boolean {
  if (answers.length < questionCount) return false
  return answers.slice(0, questionCount).every((a) => a.trim().length > 0)
}
