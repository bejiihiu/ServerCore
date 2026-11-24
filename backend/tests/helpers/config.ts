/**
 * default questionnaire matching the product spec, used by tests and as a
 * fallback picture of what the config carries. the live values always come
 * from the backend config (env), never from here.
 */
export const QUESTIONNAIRE_DEFAULTS = {
  ru: [
    "Как давно играешь в Minecraft?",
    "Почему хочешь присоединиться?",
    "Как часто планируешь играть?",
    "Есть ли опыт игры на подобных серверах?",
  ],
  en: [
    "How long have you been playing Minecraft?",
    "Why do you want to join?",
    "How often do you plan to play?",
    "Do you have experience on similar servers?",
  ],
} as const

export const QUESTIONNAIRE_DEFAULTS_MUTABLE: { ru: string[]; en: string[] } = {
  ru: [...QUESTIONNAIRE_DEFAULTS.ru],
  en: [...QUESTIONNAIRE_DEFAULTS.en],
}
