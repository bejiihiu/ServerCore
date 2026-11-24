import type { ConversationData, VersionedState, VersionedStateStorage } from "@grammyjs/conversations"
import { ConversationStateModel } from "./models/conversation-state.js"

/**
 * mongo-backed storage adapter for grammY conversations v2. implements the
 * plugin's VersionedStateStorage<string, ConversationData> contract, keyed
 * by chat id, so an in-progress questionnaire survives a bot restart.
 */
export class MongoConversationStorage implements VersionedStateStorage<string, ConversationData> {
  async read(key: string): Promise<VersionedState<ConversationData> | undefined> {
    const doc = await ConversationStateModel.findOne({ key }).lean()
    if (!doc) return undefined
    return doc.state as VersionedState<ConversationData>
  }

  async write(key: string, state: VersionedState<ConversationData>): Promise<void> {
    await ConversationStateModel.updateOne(
      { key },
      // the plugin owns the shape of the versioned payload, we store it as-is
      { $set: { state: state as unknown as Record<string, unknown> } },
      { upsert: true },
    )
  }

  async delete(key: string): Promise<void> {
    await ConversationStateModel.deleteOne({ key })
  }
}
