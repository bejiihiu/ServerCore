import { model, Schema } from "mongoose"

/**
 * in-progress questionnaire answers. durable on purpose: the applicant can
 * close the bot mid-form and resume later, and the flow survives restarts.
 * one draft per telegram account.
 */
export interface ApplicationDraft {
  telegramId: string
  minecraftUuid: string
  username: string
  /** index of the next unanswered question */
  step: number
  /** raw answers indexed by question order */
  answers: string[]
  createdAt: Date
  updatedAt: Date
}

const applicationDraftSchema = new Schema<ApplicationDraft>(
  {
    telegramId: { type: String, required: true },
    minecraftUuid: { type: String, required: true },
    username: { type: String, required: true },
    step: { type: Number, required: true, min: 0 },
    answers: { type: [String], default: [] },
  },
  { timestamps: true },
)

applicationDraftSchema.index({ telegramId: 1 }, { unique: true })

export const ApplicationDraftModel = model<ApplicationDraft>(
  "ApplicationDraft",
  applicationDraftSchema,
  "application_drafts",
)
