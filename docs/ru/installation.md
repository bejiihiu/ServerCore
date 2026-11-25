# Установка

**Языки:** [English](../installation.md) | Русский

Это руководство описывает полное развёртывание: MongoDB, backend, Telegram-бот и сервер Folia с
плагином. Все команды приведены как примеры — подставьте свои пути и имена.

> **О версиях.** Семантических версий и релизов вида `v1.0.0` нет: каждая сборка называется
> `build-<commitCount>-<shortSha>`, и этот тег является именем релиза. Если на странице
> [Releases](https://github.com/bejiihiu/ServerCore/releases) ещё нет отмеченной сборки, разверните
> backend из исходников и соберите jar плагина локально — всё остальное ниже не меняется.
>
> Спешите? [Первый запуск](first-run.md) — короткий чеклист оператора; эта страница — полное пошаговое
> руководство.

## Требования

- Linux-хост (или контейнер) для backend
- Сервер Folia **версии 26.3 или новее**
- **Java 25**, установленная для сервера Minecraft
- **[Bun](https://bun.sh)**, установленный для backend — Node.js не используется
- **MongoDB 7.x или новее**, доступная с backend
- Аккаунт Telegram для создания бота

## 1. MongoDB

Разверните экземпляр MongoDB и отдельную базу. На проде рекомендуется replica set, чтобы транзакции и
change streams вели себя предсказуемо.

```js
// in mongosh, once connected
use servercore

// optional dedicated application user
db.createUser({
  user: "servercore",
  pwd: "CHANGE_ME_STRONG_PASSWORD",
  roles: [ { role: "readWrite", db: "servercore" } ]
})
```

Замечания:

- Коллекции и индексы создаются автоматически при первом старте backend.
- Для временных данных (запросы авторизации и подобное) используются TTL-индексы. Не отключайте их.
- Регулярно делайте бэкапы базы — это единственный источник истины по аккаунтам, голосованиям и
  истории аудита.

## 2. Telegram-бот

1. Откройте [@BotFather](https://t.me/BotFather) и выполните `/newbot`. Сохраните выданный токен.
2. Отключите privacy mode, чтобы бот видел события группы: `/setprivacy` → выберите бота → **Disable**.
   Это нужно для подтверждения входов и выходов в закрытой группе сообщества.
3. Создайте закрытую группу сообщества, добавьте в неё бота и выдайте ему права администратора
   (управление приглашениями и удаление участников).
4. Запишите chat ID группы (его можно получить, отправив сообщение в группу и посмотрев лог backend,
   либо любым стандартным хелпером Bot API).

## 3. Backend

Установка из релизов (рекомендуется) или из исходников.

### Из бандла релиза

Скачайте последний бандл backend со страницы [GitHub Releases](https://github.com/bejiihiu/ServerCore/releases) —
`servercore-backend-<tag>.tar.gz`, где `<tag>` — id сборки, например
`servercore-backend-build-1234-a1b2c3d.tar.gz`. Сверьте его с `SHA256SUMS.txt` из того же релиза,
распакуйте и запустите через Bun:

```bash
sha256sum -c SHA256SUMS.txt        # run inside the directory holding the downloaded files
mkdir servercore-backend
tar -xzf servercore-backend-*.tar.gz -C servercore-backend
cd servercore-backend
cp .env.example .env
# edit .env — see the table below and docs/configuration.md
bun install --frozen-lockfile --production
bun run start
```

В бандле лежат исходники, `package.json`, `bun.lock`, `.env.example` и `Dockerfile`, поэтому из этого же
архива можно собрать образ контейнера:

```bash
docker build -t servercore-backend .
docker run --rm -p 8080:8080 -p 8081:8081 --env-file .env servercore-backend
```

### Из исходников

```bash
git clone https://github.com/bejiihiu/ServerCore.git
cd ServerCore/backend
cp .env.example .env
# edit .env
bun install
bun run start
```

### Файл окружения

Каждая переменная описана в [configuration.md](configuration.md). Минимальный продовый `.env` выглядит так
(значения — заглушки, сгенерируйте свои):

```env
MONGODB_URI=mongodb://servercore:CHANGE_ME_STRONG_PASSWORD@127.0.0.1:27017/servercore
BOT_TOKEN=123456789:AA-placeholder-from-botfather
GROUP_CHAT_ID=-1001234567890
WS_PORT=8081
SERVERCORE_SERVER_CREDENTIALS={"game-1":"replace-with-long-random-secret"}
LOG_LEVEL=info
```

HMAC-секрет для каждого сервера сгенерируйте сами и положите его в `SERVERCORE_SERVER_CREDENTIALS` — это
общая точка доверия между backend и плагином, и то же значение идёт в конфигурацию плагина:

```bash
openssl rand -hex 32
```

## 4. Плагин на сервере Folia

### Плагины сервера

| Плагин | Обязателен | Роль |
|---|---|---|
| [PacketEvents](https://packetevents.com/) | **да** | На нём держится весь пакетный слой (anti-ESP, маскировки, просмотр живого инвентаря); без него ServerCore отказывается включаться |
| [LuckPerms](https://luckperms.net/) | рекомендуется | Источник прав. Без него плагин откатывается на статическую карту из конфига (`luckperms.fallback.*`) — этого хватает для запуска, но это не полноценный менеджер групп |
| [PlaceholderAPI](https://wiki.placeholderapi.com/) | опционально | Отдаёт плейсхолдеры `%servercore_…%` другим плагинам; без него их просто нет |
| [FlectonePulse](https://github.com/FlectonePulse/FlectonePulse) | опционально | Источник чата и модерации. Без него ServerCore работает без проверок наказаний и сам отправляет свои ops-события в Telegram |

### Шаги

1. Скачайте jar плагина со страницы [GitHub Releases](https://github.com/bejiihiu/ServerCore/releases) —
   файл называется `ServerCore-<tag>.jar` (например, `ServerCore-build-1234-a1b2c3d.jar`). Сверьте его с
   `SHA256SUMS.txt` из того же релиза и положите в `plugins/`.
2. Установите PacketEvents (и опциональные плагины, если нужны) в `plugins/`.
3. Запустите сервер один раз. Плагин создаст `plugins/ServerCore/config.yml`, но на этом первом запуске
   выключится сам: `backend.url` и `backend.secret` пустые, а нерабочий блок `backend` блокирует загрузку
   (в логе будет `config error: …`).
4. Отредактируйте конфигурацию:

```yaml
# plugins/ServerCore/config.yml
backend:
  url: "wss://mc-api.example.com:8081/ws"
  server-id: "game-1"          # must match SERVERCORE_SERVER_CREDENTIALS on the backend
  secret: "the-hex-secret-you-generated"

guest:
  enabled: true

anti-esp:
  enabled: true
  max-distance: 48
  check-interval-ticks: 10
```

Полный справочник ключей: [configuration.md](configuration.md).

5. Перезапустите сервер. При старте плагин открывает исходящее подключение к gateway backend
   (`<scheme>://<host>:<backend WS_PORT>/ws` — на любом другом пути gateway отвечает `404`). В логе
   плагина появится `backend gateway connected as game-1`, а лог backend ответит
   `game server connected, awaiting hello`. Если же плагин продолжает писать
   `backend gateway disconnected`, рукопожатие отклоняется — backend называет причину
   (`unknown server id`, `signature mismatch`, `timestamp outside replay window`).

`docs/ru/first-run.md` сжимает весь подъём backend + плагина в чеклист с точными строками лога, которые
надо искать.

## 5. Укрепление сервера Folia

ServerCore рассчитан на укреплённый сервер. Перед выходом в прод:

- Убедитесь, что **ни один игровой аккаунт не имеет OP**. Статус оператора не является механизмом
  администрирования — права живут в LuckPerms.
- Установите [LuckPerms](https://luckperms.net/) и создайте структуру групп (например, `default`,
  `trusted`, `helper`, `moderator`, `admin`, `owner`).
- Выдавайте персоналу только те права ServerCore, которые ему действительно нужны:
  - `servercore.admin`
  - `servercore.spec`
  - `servercore.spec.other`
  - `servercore.guest.manage`
  - `servercore.account.manage`
- Установите [FlectonePulse](https://github.com/FlectonePulse/FlectonePulse) для чата и модерации. Если у
  него включён Telegram-модуль, ServerCore это определит и не будет дублировать свои ops-объявления в
  Telegram (`flectonepulse.disable-tg`, включено по умолчанию), чтобы ничего не объявлялось дважды.
- При желании установите PlaceholderAPI, чтобы открыть плейсхолдеры ServerCore другим плагинам.

## 6. Проверка развёртывания

1. Зайдите на сервер со свежим аккаунтом. Вы должны попасть в гостевой режим наблюдателя и получить в
   чате личную deep-ссылку на Telegram.
2. Откройте ссылку, пройдите `/start` в боте и подтвердите привязку.
3. Заполните заявку. Участники должны получить голосование в течение нескольких секунд.
4. После успешного голосования примите одноразовое приглашение в группу, переподключитесь и подтвердите
   запрос на вход в Telegram.
5. Проверьте, что `audit_logs` и `server_events` наполняются, а в логе backend идут чистые
   heartbeat-кадры от плагина.

## Обновление

- Плагин: либо замените jar вручную (остановить, заменить, запустить — снапшоты и сессии переживают
  перезапуски по замыслу), либо дайте это сделать встроенному апдейтеру: `ops.update.*` опрашивает
  релизы GitHub на теги `build-*`, берёт самый новый, скачивает `ServerCore-<tag>.jar`, сверяет его с
  `SHA256SUMS.txt` из релиза и подменяет работающий файл (`.backup/` хранит последние три).
  `ops.update.auto-restart` перезапускает сервер после проверенной замены. Апдейтер движется только
  вперёд — релиз с меньшим счётчиком коммитов игнорируется — и отказывается от любого jar, чей SHA-256
  отсутствует в файле контрольных сумм или не совпадает.
- Backend: остановите процесс, замените бандл (или сделайте `git pull`), сохраните `.env`, запустите
  снова. Работающие инстансы переподключаются автоматически, когда backend возвращается.
