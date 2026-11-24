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
    /** how many times the voting was auto-extended for missing quorum */
    extensionCount: number
    /** filled in when the voting is decided */
    votesCast: number | null
    quorumReached: boolean | null
    decidedAt: Date | null
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
      votesCast: { type: Number, default: null },
      quorumReached: { type: Boolean, default: null },
      decidedAt: { type: Date, default: null },
      extensionCount: { type: Number, required: true, min: 0, default: 0 },
    },
  },
  { timestamps: true },
)

// one live application per player at a time: the partial unique index only
// bites while the application is still PENDING or VOTING. this is the
// db-level anti-double-submit guard
applicationSchema.index(
  { minecraftUuid: 1, status: 1 },
  { unique: true, partialFilterExpression: { status: { $in: ["PENDING", "VOTING"] } } },
)
applicationSchema.index({ "voting.expiresAt": 1 })

export const ApplicationModel = model<Application>("Application", applicationSchema, "applications")
