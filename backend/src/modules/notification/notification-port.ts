/**
 * the fan-out contract account modules depend on. the real implementation is
 * src/services/notification-service.ts (rate-limited broadcast engine); the
 * app composition root adapts it onto this interface. tests use recording
 * fakes.
 */
export interface NotificationFanout {
  /** personal delivery to one user; failures are swallowed upstream */
  notifyUser(chatId: string, text: string): Promise<void>
  /** same text to every configured admin chat; per-chat failures isolated */
  notifyAdmins(text: string): Promise<void>
}
