import { model, Schema } from "mongoose"

export interface GuestSnapshot {
  sessionId: string
  minecraftUuid: string
  world: string
  location: {
    x: number
    y: number
    z: number
    yaw: number
    pitch: number
  }
  gameMode: string
  flying: boolean
  flySpeed: number
  walkSpeed: number
  createdAt: Date
}

const guestSnapshotSchema = new Schema<GuestSnapshot>(
  {
    // snapshot is bound to a concrete session — one snapshot per session, ever.
    // a second join event must never overwrite the original location
    sessionId: { type: String, required: true },
    minecraftUuid: { type: String, required: true },
    world: { type: String, required: true },
    location: {
      x: { type: Number, required: true },
      y: { type: Number, required: true },
      z: { type: Number, required: true },
      yaw: { type: Number, required: true },
      pitch: { type: Number, required: true },
    },
    gameMode: { type: String, required: true },
    flying: { type: Boolean, required: true },
    flySpeed: { type: Number, required: true },
    walkSpeed: { type: Number, required: true },
  },
  { timestamps: true },
)

guestSnapshotSchema.index({ sessionId: 1 }, { unique: true })
guestSnapshotSchema.index({ minecraftUuid: 1, createdAt: -1 })

export const GuestSnapshotModel = model<GuestSnapshot>("GuestSnapshot", guestSnapshotSchema, "guest_snapshots")
