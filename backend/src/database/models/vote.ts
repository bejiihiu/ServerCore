import { model, Schema, Types } from "mongoose"

export const VOTE_DECISIONS = ["ACCEPT", "REJECT"] as const

export type VoteDecision = (typeof VOTE_DECISIONS)[number]

export interface Vote {
  applicationId: Types.ObjectId
  voterUuid: string
  decision: VoteDecision
  createdAt: Date
}

const voteSchema = new Schema<Vote>(
  {
    applicationId: { type: Schema.Types.ObjectId, required: true, ref: "Application" },
    voterUuid: { type: String, required: true },
    decision: { type: String, enum: VOTE_DECISIONS, required: true },
  },
  { timestamps: true },
)

// one player, one vote, one application — enforced by the db itself
voteSchema.index({ applicationId: 1, voterUuid: 1 }, { unique: true })

export const VoteModel = model<Vote>("Vote", voteSchema, "votes")
