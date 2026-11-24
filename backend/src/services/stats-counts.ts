import { ApplicationModel, UserModel } from "../database/models/index.js"

/**
 * live domain counters the /stats dashboard aggregates on top of the
 * gateway/directory view. two cheap countDocuments calls per admin command —
 * atlas m0 sizes make exact counts affordable, and a wrong number is worse
 * than a slow one here.
 *
 * this answers "how many users/votings are there". the storage side — how
 * many documents and how much disk each collection eats — is a different
 * question and lives in modules/retention/collection-stats.ts. keep them
 * apart: the moment one class serves both, a /stats change touches the
 * retention sweep.
 */
export interface StatsCountsSource {
  /** accounts with status ACTIVE: the actual admitted player base */
  activeUsers(): Promise<number>
  /** applications whose voting window is still running */
  openVotings(): Promise<number>
}

export class MongoStatsCounts implements StatsCountsSource {
  async activeUsers(): Promise<number> {
    return UserModel.countDocuments({ status: "ACTIVE" })
  }

  async openVotings(): Promise<number> {
    return ApplicationModel.countDocuments({ status: "VOTING" })
  }
}
