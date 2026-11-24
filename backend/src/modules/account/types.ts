import { USER_STATUSES, type UserStatus } from "../../database/models/user.js"

export { USER_STATUSES }
export type { UserStatus }

/**
 * allowed account status transitions. the lifecycle is a one-way ladder
 * (unregistered -> application -> voting -> approved -> active) with the
 * moderation states (suspended / banned / deletion pending) reachable from
 * any live state. anything not listed here is rejected.
 */
export const ALLOWED_TRANSITIONS: Readonly<Record<UserStatus, readonly UserStatus[]>> = {
  UNREGISTERED: ["APPLICATION_PENDING", "DELETION_PENDING"],
  APPLICATION_PENDING: ["VOTING", "DELETION_PENDING"],
  VOTING: ["APPROVED", "APPLICATION_PENDING", "DELETION_PENDING"],
  APPROVED: ["ACTIVE", "DELETION_PENDING"],
  ACTIVE: ["SUSPENDED", "BANNED", "DELETION_PENDING"],
  SUSPENDED: ["ACTIVE", "BANNED", "DELETION_PENDING"],
  // even banned accounts can request deletion; data hygiene beats grudges
  BANNED: ["DELETION_PENDING"],
  DELETION_PENDING: [],
}

/** statuses a live session may exist in and that linking/admission can touch */
export const LINKABLE_STATUSES: readonly UserStatus[] = [
  "UNREGISTERED",
  "APPLICATION_PENDING",
  "VOTING",
  "APPROVED",
  "ACTIVE",
]

/** statuses that freeze the account: no linking, no admission, no sessions */
export const FROZEN_STATUSES: readonly UserStatus[] = ["SUSPENDED", "BANNED", "DELETION_PENDING"]

export type AccountErrorCode =
  | "ACCOUNT_NOT_FOUND"
  | "ACCOUNT_FROZEN"
  | "INVALID_TRANSITION"
  | "ALREADY_LINKED"
  | "UUID_TAKEN"
  | "TOKEN_INVALID"
  | "OWNERSHIP_NOT_PROVEN"
  | "SERVER_OFFLINE"
  | "RATE_LIMITED"
  | "DELETION_FAILED"
  | "TELEGRAM_GROUP_UNAVAILABLE"

export class AccountError extends Error {
  readonly code: AccountErrorCode
  readonly details?: Record<string, unknown>

  constructor(code: AccountErrorCode, message: string, details?: Record<string, unknown>) {
    super(message)
    this.name = "AccountError"
    this.code = code
    this.details = details
  }
}

export function isTransitionAllowed(from: UserStatus, to: UserStatus): boolean {
  return ALLOWED_TRANSITIONS[from].includes(to)
}

/** throws unless from -> to is on the ladder; the single choke point for status changes */
export function assertTransition(from: UserStatus, to: UserStatus): void {
  if (!isTransitionAllowed(from, to)) {
    throw new AccountError("INVALID_TRANSITION", `transition ${from} -> ${to} is not allowed`, { from, to })
  }
}

export function isFrozen(status: UserStatus): boolean {
  return FROZEN_STATUSES.includes(status)
}
