/**
 * pure voting math, no io. quorum: strictly more than half of the eligible
 * voters must cast a vote (exactly 50% is not a quorum). result: strictly
 * more than half of the valid votes must be in favour, ties reject.
 */

export function hasQuorum(votesCast: number, eligibleVoters: number): boolean {
  return votesCast * 2 > eligibleVoters
}

export function isApproved(votesFor: number, votesAgainst: number): boolean {
  return votesFor * 2 > votesFor + votesAgainst
}

export type VotingOutcome = "APPROVED" | "REJECTED" | "EXPIRED"

/**
 * decides the outcome of a finished voting. quorum not reached means EXPIRED
 * (handed to admin review), never an automatic approval.
 */
export function decideOutcome(
  votesFor: number,
  votesAgainst: number,
  eligibleVoters: number,
): VotingOutcome {
  const votesCast = votesFor + votesAgainst
  if (!hasQuorum(votesCast, eligibleVoters)) return "EXPIRED"
  return isApproved(votesFor, votesAgainst) ? "APPROVED" : "REJECTED"
}

/** a voting with no quorum gets exactly one auto-extension before expiring */
export const MAX_VOTING_EXTENSIONS = 1

export function shouldAutoExtend(votesCast: number, eligibleVoters: number, extensionCount: number): boolean {
  return !hasQuorum(votesCast, eligibleVoters) && extensionCount < MAX_VOTING_EXTENSIONS
}
