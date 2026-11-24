import type { ServerState } from "../websocket/protocol/message-types.js"
import type { Logger } from "../logging/logger.js"
import { t, type Locale } from "./i18n.js"
import type { NotificationService } from "./notification-service.js"

/**
 * ops events the backend reacts to with a broadcast. SCHEDULED_RESTART is a
 * plugin-side distinction (daily cron restart vs update-triggered restart),
 * both travel as SERVER_STATUS RESTARTING with a different detail string.
 */
export const OPS_EVENTS = [
  "STARTING",
  "RUNNING",
  "RESTARTING",
  "SCHEDULED_RESTART",
  "STOPPING",
  "OFFLINE",
  "UPDATE_READY",
] as const

export type OpsEvent = (typeof OPS_EVENTS)[number]

const EVENT_KEYS = {
  STARTING: "ops.server.starting",
  RUNNING: "ops.server.running",
  RESTARTING: "ops.server.restarting",
  SCHEDULED_RESTART: "ops.server.restarting_scheduled",
  STOPPING: "ops.server.stopping",
  OFFLINE: "ops.server.offline",
  UPDATE_READY: "ops.server.update_ready",
} as const satisfies Record<OpsEvent, string>

/** maps a wire SERVER_STATUS state to the ops event it should announce */
export function eventForState(state: ServerState, detail?: string): OpsEvent {
  if (state === "RESTARTING" && detail === "SCHEDULED") return "SCHEDULED_RESTART"
  return state
}

/**
 * turns ops events into localized broadcasts: every linked user gets the dm,
 * admin chat gets the same text. dedupes repeated states per server so a
 * chatty plugin cannot spam the userbase.
 */
export class OpsNotifier {
  private readonly notifications: NotificationService | null
  private readonly adminChatIds: string[]
  private readonly locale: Locale
  private readonly logger: Logger
  private readonly lastEventByServer = new Map<string, OpsEvent>()

  constructor(deps: {
    /** null when the bot is not configured: events degrade to log lines */
    notifications: NotificationService | null
    adminChatIds: readonly string[]
    locale: Locale
    logger: Logger
  }) {
    this.notifications = deps.notifications
    this.adminChatIds = [...deps.adminChatIds]
    this.locale = deps.locale
    this.logger = deps.logger
  }

  /** broadcasts an ops event unless the same event was already announced */
  async announce(
    serverId: string,
    event: OpsEvent,
    options: { force?: boolean; telegram?: boolean } = {},
  ): Promise<void> {
    if (!options.force && this.lastEventByServer.get(serverId) === event) return
    this.lastEventByServer.set(serverId, event)

    const text = t(this.locale, EVENT_KEYS[event])
    this.logger.info({ serverId, event }, "ops event")

    // telegram: false = the game server asked us to stay quiet (its
    // flectonepulse integration owns announcements right now)
    if (this.notifications === null || options.telegram === false) return
    try {
      await this.notifications.broadcast(text, this.adminChatIds)
    } catch (error) {
      // a failed broadcast must never break the ops flow itself
      this.logger.error({ err: error, serverId, event }, "ops broadcast failed")
    }
  }

  /** crash alert: admin chat always, optionally all users (configurable) */
  async offlineAlert(serverId: string, minutesUnreachable: number, notifyUsers: boolean): Promise<void> {
    const adminText = t(this.locale, "ops.admin.offline_alert", { server: serverId, minutes: minutesUnreachable })
    this.logger.warn({ serverId, minutesUnreachable }, "server presumed offline")

    if (this.notifications === null) return
    try {
      await this.notifications.broadcast(adminText, this.adminChatIds)
      if (notifyUsers) {
        // users get the generic wording, not the ops jargon
        await this.notifications.broadcast(t(this.locale, EVENT_KEYS.OFFLINE))
      }
    } catch (error) {
      this.logger.error({ err: error, serverId }, "offline alert failed")
    }
  }

  /** crash resolved: the all-clear goes to the admin chat with server detail,
   * every linked user gets the generic recovery line (mirror of the offline
   * path — a crash alert that is never lifted is worse than none) */
  async serverBack(serverId: string): Promise<void> {
    this.logger.info({ serverId }, "server back online")
    if (this.notifications === null) return
    try {
      await this.notifications.notifyAdmins(t(this.locale, "ops.admin.server_back", { server: serverId }), this.adminChatIds)
      await this.notifications.broadcast(t(this.locale, EVENT_KEYS.RUNNING), this.adminChatIds)
    } catch (error) {
      // a failed broadcast must never break the ops flow itself
      this.logger.error({ err: error, serverId }, "server-back broadcast failed")
    }
  }

  /** clears the dedupe memory so a reconnecting server re-announces */
  forget(serverId: string): void {
    this.lastEventByServer.delete(serverId)
  }
}
