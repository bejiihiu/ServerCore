import { model, Schema } from "mongoose"

export const APPLICATION_STATUSES = ["PENDING", "VOTING", "APPROVED", "REJECTED", "EXPIRED"] as const

export type ApplicationStatus = (typeof APPLICATION_STATUSES)[number]

export interface Application {
  minecraftUuid: string
  telegramId: string
  answers: {
    question: string
    answer: string
  }[]
  status: ApplicationStatus
  voting: {
    startedAt: Date
    expiresAt: Date
    eligibleVoters: number
    votesFor: number
    votesAgainst: number
    abstentions: number
  }
  createdAt: Date
}

const applicationSchema = new Schema<Application>(
  {
    minecraftUuid: { type: String, required: true },
    telegramId: { type: String, required: true },
    answers: {
      type: [
        {
          question: { type: String, required: true },
          answer: { type: String, required: true },
        },
      ],
      default: [],
    },
    status: { type: String, enum: APPLICATION_STATUSES, required: true },
    voting: {
      startedAt: { type: Date, required: true },
      expiresAt: { type: Date, required: true },
      eligibleVoters: { type: Number, required: true, min: 0 },
      votesFor: { type: Number, required: true, min: 0, default: 0 },
      votesAgainst: { type: Number, required: true, min: 0, default: 0 },
      abstentions: { type: Number, required: true, min: 0, default: 0 },
    },
  },
  { timestamps: true },
)

// a player can only have one live application at a time
applicationSchema.index({ minecraftUuid: 1, status: 1 })
applicationSchema.index({ "voting.expiresAt": 1 })

export const ApplicationModel = model<Application>("Application", applicationSchema, "applications")
