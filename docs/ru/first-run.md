# Первый запуск

**Языки:** [English](../first-run.md) | Русский

Короткий чеклист оператора для первого запуска ServerCore: один backend, один сервер Folia, один
плагин. Полная версия со всеми опциями — в [installation.md](installation.md), все ключи — в
[configuration.md](configuration.md).

## 0. Что понадобится

| Компонент | Обязателен | Версия / примечание |
|---|---|---|
| Сервер Folia | **да** | **26.3 или новее** — плагин объявляет `api-version: 26.3` и `folia-supported: true`. Paper не поддерживается |
| Java | **да** | **25** — плагин собран под toolchain Java 25 и не загрузится на более старом рантайме |
| PacketEvents | **да** | **2.14.x** — жёсткая зависимость плагина (`required: true`); на нём держится весь пакетный слой (anti-ESP, маскировки, живой инвентарь), без него плагин отказывается включаться |
| Bun + MongoDB | **да** (для backend) | Bun 1.4+ (`package.json` engines), MongoDB 7.x или новее |
| Telegram-бот | рекомендуется | Токен от [@BotFather](https://t.me/BotFather) с **выключенным** privacy mode; без токена backend работает без бота (`bot is disabled`) |
| LuckPerms | рекомендуется | Источник прав. Без него плагин откатывается на статическую карту `luckperms.fallback.*` — запуститься он запустится, но это не менеджер групп |
| PlaceholderAPI | опционально | Только отдаёт `%servercore_…%` другим плагинам; нет плагина — нет плейсхолдеров |
| FlectonePulse | опционально | Источник чата и модерации, API 1.15.x. Нет плагина — нет проверок наказаний |

## 1. Разложить файлы

| Файл релиза | Куда |
|---|---|
| `ServerCore-<tag>.jar` | в каталог `plugins/` сервера |
| `servercore-backend-<tag>.tar.gz` | в любой каталог на хосте backend — распаковать (`tar -xzf …`) |
| `SHA256SUMS.txt` | держать рядом с двумя предыдущими и сначала прогнать `sha256sum -c SHA256SUMS.txt` |

`<tag>` — это id сборки, `build-<commitCount>-<shortSha>`. Семантических версий нет.

## 2. Backend

Минимум, который реально работает (остальное закрывают значения по умолчанию — см. [configuration.md](configuration.md)):

```env
MONGODB_URI=mongodb://127.0.0.1:27017/servercore
BOT_TOKEN=123456789:AA…                 # from BotFather
GROUP_CHAT_ID=-1001234567890            # closed community group
SERVERCORE_SERVER_CREDENTIALS={"game-1":"<openssl rand -hex 32>"}
```

- Ключ в `SERVERCORE_SERVER_CREDENTIALS` должен совпадать с `backend.server-id` плагина, а секрет —
  с `backend.secret` плагина, и быть не короче 32 символов. Эта пара — единственная точка доверия
  между двумя сторонами.
- `WS_PORT` оставьте `8081`, если на этом порту уже никто не слушает. Плагин ходит на
  `<scheme>://<host>:<WS_PORT>/ws` — на любом другом пути gateway отвечает `404`.
- Запуск: `bun install --frozen-lockfile --production && bun run start`. Коллекции и индексы
  создаются при первом старте.

Ожидаемые строки лога, по порядку (`bot polling started` появляется асинхронно и может прийти чуть позже):

```text
mongodb connected
websocket gateway listening     { port: 8081 }
admission indexes synced        (or a warning if mongo refused index creation)
http listening                  { port: 8080, host: '0.0.0.0' }
bot polling started             { username: '…' }
servercore backend started
```

Проверьте health-эндпоинт — `200` с `"status":"ok"` означает, что Mongo поднята, а `connectedServers` —
список игровых серверов, подключённых сейчас:

```bash
curl -s http://127.0.0.1:8080/health
```

`503` / `"degraded"` означает, что Mongo недоступна: процесс намеренно остаётся живым, но ничего не
заработает, пока связь не вернётся.

## 3. Плагин

1. Положите `ServerCore-<tag>.jar` и PacketEvents в `plugins/`, затем запустите сервер **один раз**.
2. Плагин создаст `plugins/ServerCore/config.yml` и выключится сам. Это ожидаемый первый запуск:
   `backend.url` и `backend.secret` пустые, а нерабочий блок `backend` блокирует загрузку. Ищите в логе:

   ```text
   config error: backend.url is empty: set the backend gateway websocket url (ws:// or wss://)
   ```

3. Для начала отредактируйте только блок `backend`:

   ```yaml
   backend:
     url: "wss://mc-api.example.com:8081/ws"   # ws:// for a local backend on the same host
     server-id: "game-1"                       # matches the SERVERCORE_SERVER_CREDENTIALS key
     secret: "<the same 32+ char secret>"
   ```

   `guest.enabled`, `anti-esp.enabled`, `spec.enabled` и `protection.enabled` по умолчанию `true`,
   так что правок только в блоке `backend` достаточно для полного набора функций.
4. Перезапустите сервер. В логе должны появиться строки в таком порядке:

   ```text
   backend gateway connected as game-1
   servercore build-<n>-<sha> enabled (guest mode: on, flectonepulse: not_present)
   ```

   `flectonepulse:` принимает значения `not_present`, `incompatible`, `connecting` или `connected` —
   это состояние интеграции только для чтения, а не ошибка.

## 4. Проверить связку

Показания обеих сторон должны совпасть — проверьте обе.

| Где | Признак |
|---|---|
| Лог backend | `game server connected, awaiting hello`, затем `server state changed { state: 'STARTING' \| 'RUNNING' }` |
| Backend `/health` | id сервера появился в `connectedServers` |
| Лог плагина | `backend gateway connected as game-1`; после входа игрока на backend появляется `player joined` |

Если вместо этого плагин в цикле пишет `backend gateway disconnected, reconnect scheduled`, значит
рукопожатие отклоняется ещё до создания сокета. Причина лежит в логе backend сразу после
`gateway handshake rejected`:

| Причина | Что не так |
|---|---|
| `unknown server id` | у `backend.server-id` нет соответствующего ключа в `SERVERCORE_SERVER_CREDENTIALS` |
| `signature mismatch` | секреты плагина и backend различаются |
| `timestamp outside replay window` | часы двух хостов расходятся более чем на 30 с (NTP) |
| `missing handshake fields` | `backend.url` указывает не на путь gateway, либо прокси срезал query-строку |

### Спросить плагин, а не лог

`/servercore diag` отвечает на вопрос «почему не работает» одной карточкой:

- **сборка** — версия плагина, jar, из которого он загружен, версия сервера, Folia да/нет, версия Java;
- **модули** — каждый узел как *включён*, *выключен в конфиге*, *нет зависимости* или *сбой*:
  гостевой режим, пакетный слой, anti-ESP, защита, spec, бары, LuckPerms, PlaceholderAPI, FlectonePulse;
- **связь с бэкендом** — есть ли соединение, возраст последнего heartbeat, глубина outbox,
  причина последнего разрыва и число попыток переподключения;
- **счётчики** — активные гости, живые spec-сеансы, включённые бары, игроки в сети;
- **версии** — PacketEvents, LuckPerms, PlaceholderAPI и FlectonePulse;
- **вердикт** — `OK`, `WARN` или `FAIL`, находки за ним и три ближайших действия.

Консоль получает обычные строки, игрок — карточку в рамке. `/servercore diag --json` выводит тот же отчёт
одним машиночитаемым документом (стабильные ключи, статусы модулей и коды находок — имена констант):
его можно приложить к тикету вместо скриншотов.

## 5. Проверить сквозной сценарий

1. Зайдите со свежим аккаунтом → гостевой режим наблюдателя плюс личная deep-ссылка на Telegram в чате.
2. Откройте ссылку, выполните `/start` в боте, подтвердите привязку (на стороне плагина —
   `link token issued for <name>`).
3. Заполните заявку → группа получает голосование (ожидаемые фоновые строки — `player joined`,
   `guest session started`).
4. После кворума примите одноразовое приглашение в группу, переподключитесь и подтвердите запрос на
   вход в Telegram.
5. `audit_logs` и `server_events` на backend начинают наполняться; `/stats` в Telegram отвечает
   админам из `ADMIN_TELEGRAM_IDS`.

## 6. Где лежат состояние и логи

| Что | Где |
|---|---|
| Конфиг плагина | `plugins/ServerCore/config.yml` |
| Состояние плагина | `plugins/ServerCore/` — `guest-snapshots.json` (исходное состояние игроков), `guest-session-counts.json` (лимит гостевых сессий), `account-cache.json` (последние известные вердикты по аккаунтам), `ws-outbox.json` (кадры, отложенные на время обрыва связи), `spec-restore-journal.json` (состояние персонала для восстановления), `locales/ru.yml` (языковой бандл, присланный backend) |
| Аудит наблюдений (сессии `/spec` только для чтения) | `plugins/ServerCore/audit/spec-audit-YYYY-MM-DD.jsonl`, ротация ежедневно, хранение 30 дней (`spec.audit.*`) |
| Логи плагина | собственный `logs/latest.log` сервера — плагин пишет через логгер сервера |
| Стейджинг обновлений / бэкапы | `plugins/ServerCore/.updates/`, `plugins/ServerCore/.backup/` (последние три jar) |
| Аудит и события backend | коллекции MongoDB `audit_logs` и `server_events` (по умолчанию хранение 90 дней) |
| Логи backend | stdout процесса, JSON от Pino с отметками времени Europe/Moscow (`LOG_LEVEL`, `LOG_TIMEZONE`) |
| Health | `GET /health` на `PORT` (8080) |

## 7. Известные ловушки первого запуска

- **Что-то не работает, а лог длинный.** Сначала `/servercore diag`: он называет модуль, который не поднялся
  (`disabled by config`, `absent dependency` или `failed`), показывает состояние связи с глубиной outbox и
  три ближайших подсказки. `/servercore diag --json` — тот же отчёт для тикета.
- **Никакого OP.** Статус оператора здесь не является механизмом администрирования; права приходят из
  LuckPerms (`servercore.admin`, `servercore.spec`, …). `/servercore deop-all` и `/servercore groups`
  разрешены из консоли.
- **`guest.enabled: false`** никого не пускает внутрь — неавторизованные входы отклоняются с сообщением
  о регистрации вместо гостевого режима наблюдателя.
- **Защищённые команды заблокированы для всех**, включая операторов (`protection.blocked-commands`).
- **Апдейтер включён по умолчанию** (`ops.update.enabled: true`, `ops.update.repo` указывает на этот
  репозиторий). Поэтому свежая установка опрашивает релизы GitHub каждые 30 минут и ставит только ту
  сборку, у которой счётчик коммитов больше, чем у работающей.
- **`.env` никогда не едет вместе с бандлом.** После каждой распаковки нужен свой `cp .env.example .env`
  и правка.
