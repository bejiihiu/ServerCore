# Аудит зависимостей и цепочки поставок

**Языки:** [English](../dependency-audit.md) | Русский

Запись регулярного прохода по зависимостям, лицензиям и цепочке поставок этого репозитория. Это снимок:
перезапускайте команды ниже после любого изменения зависимостей и обновляйте находки.

**Последний прогон:** 2026-11-25 · backend Bun 1.4.2 · плагин Kotlin 2.4.21 / Gradle 9.9.2 / JDK 25

## Как это запускается

```bash
# backend
cd backend && bun install --frozen-lockfile && bun audit && bun outdated

# plugin — every resolved coordinate of the configurations that matter
./gradlew :plugin:dependencies --configuration runtimeClasspath
./gradlew :plugin:dependencies --configuration compileClasspath
./gradlew :plugin:dependencies --configuration testClasspath

# advisories: OSV batch query, Maven ecosystem, one query per resolved coordinate
curl -s https://api.osv.dev/v1/querybatch -H 'content-type: application/json' -d @queries.json
```

## Backend

`bun audit` сообщает **об отсутствии advisories** по всем 94 проверяемым пакетам. `bun outdated` показывает
один безопасный патч и один мажор:

| Пакет | Зафиксировано | Доступно | Действие |
|---|---|---|---|
| `mongoose` | 9.13.0 | 9.13.1 | поднят до 9.13.1 (патч, lockfile обновлён) |
| `typescript` (dev) | 5.9.3 | 7.0.2 | не трогали — мажорная версия, вне рамок прохода по безопасности |

Lockfile закоммичен (`backend/bun.lock`) и `bun install --frozen-lockfile` проходит, поэтому CI и
релизный workflow ставят ровно то дерево, которое проверялось.

## Плагин

По compile- и test-classpath разрешается 113 координат. Пакетный запрос к OSV вернул 68 отдельных
advisories по 19 из них — все они достижимы через `compileOnly` API, то есть артефакты, которые
предоставляет сервер и которых в jar никогда нет. Runtime-classpath (четыре артефакта, реально
зашейденных внутрь) чист.

| Компонент | Версия | Откуда приходит | Advisories | Шиппится | Вердикт |
|---|---|---|---|---|---|
| `io.netty` (15 артефактов) | 4.1.72.Final | POM `packetevents-spigot:2.14.0` | 65 (1 critical, 32 high, 30 moderate, 2 low) | нет | netty предоставляет сервер; сдвинуть может только пин в собственном POM PacketEvents |
| `com.google.guava:guava` | 33.6.0-jre | `folia-api` | 1 high (CVE-2026-102554, десериализация Java) | нет | мы никогда не десериализуем недоверенные Java-объекты; исправлено в апстриме в 33.7.2 |
| `org.apache.logging.log4j:log4j-api` | 2.26.0 | `folia-api` | 1 moderate (CVE-2026-49844, JSON-кодирование `MapMessage`) | нет | нужен `JsonTemplateLayout` + контролируемые атакующим float'ы; исправлено в 2.26.1 |
| `org.apache.commons:commons-lang3` | 3.12.0 | `folia-api` → `maven-resolver-provider` | 1 moderate (CVE-2025-48924, рекурсия) | нет | только build-classpath |
| `org.codehaus.plexus:plexus-utils` | 3.5.1 | `folia-api` → `maven-resolver-provider` | 1 high (CVE-2025-67030, traversal в `extractFile`) | нет | только build-classpath, `extractFile` не вызывается |

Версии здесь намеренно не поднимались. Насильно навязать более новый netty или guava на
`compileOnly` конфигурации — значит поменять число в отчёте сборки, не изменив ни байта отгружаемого jar
или развёрнутого сервера: runtime-classpath этих библиотек принадлежит Folia и PacketEvents. Плагин
управляет тем, против чего компилируется, и это закреплено в `gradle/libs.versions.toml` и
пересматривается целиком.

Лицензионная сторона того же графа — в [../THIRD_PARTY_LICENSES.md](../../THIRD_PARTY_LICENSES.md).

## Секреты

| Проверка | Результат |
|---|---|
| отслеживаемые файлы (489) × 13 паттернов учётных данных | 21 попадание, все — тестовые фикстуры или плейсхолдеры документации (`"test-secret-0123456789abcdef…"`, `CHANGE_ME_STRONG_PASSWORD`, `"the-hex-secret-you-generated"`) |
| вся история git (3.5 МБ патчей по всем ref'ам) | 2 попадания, оба — один и тот же плейсхолдер `CHANGE_ME_STRONG_PASSWORD` в примере установки |
| `.env` когда-либо в коммитах | никогда |
| активные алерты сканирования секретов GitHub | не поступало |

Плагин читает свой HMAC-секрет из `config.yml` (по умолчанию пусто), а backend читает каждые учётные
данные из окружения; примеры и документация несут только плейсхолдеры. Сканирование секретов на стороне
GitHub для этого репозитория недоступно (нет GitHub Advanced Security), поэтому проход выше —
паттерн-ориентированный и локальный: он ловит токены форм, присущих провайдерам, приватные ключи и
строки подключения с учётными данными, но не неизвестный кастомный формат.

## Укрепление цепочки поставок

| Пункт | Состояние |
|---|---|
| действия workflow закреплены на фиксированный ref | да — `actions/checkout@v6`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `oven-sh/setup-bun@v2`, `softprops/action-gh-release@v2`. Никаких `@master`, `@main` или веток. Теги всё ещё изменяемы (см. follow-ups). |
| целостность Gradle wrapper | `distributionSha256Sum` задан для Gradle 9.9.2 и сверяется с загруженным дистрибутивом; `validateDistributionUrl=true` |
| `curl … \| bash` или выполнение удалённых скриптов в CI | нет — оба workflow запускают только `./gradlew` и `bun` |
| целостность артефактов релиза | `SHA256SUMS.txt` публикуется с каждым релизом, а апдейтер в плагине отказывается от jar с несовпадающим хешем |
| содержимое jar проверяется до релиза | да — workflow падает, если зашейденный Kotlin stdlib отсутствует или `paper-plugin.yml` не несёт тег |
| сборка контейнера | `backend/Dockerfile` ставит с `--frozen-lockfile --production`, работает не от root, имеет healthcheck |
| протокол репозиториев зависимостей | HTTPS везде (Maven Central, papermc, codemc, helpch, npm) |
| тулчейн backend в CI | `bun-version: latest` — плавает между прогонами |
| базовый образ Docker | `oven/bun:1.4-slim` — плавает в пределах линии 1.4 |
| автоматические обновления зависимостей | алерты и обновления версий Dependabot на репозитории отключены |
| верификация артефактов Gradle | нет `gradle/verification-metadata.xml`; артефакты разрешаются только по координате |

## Что дальше

1. **Включить Dependabot** (алерты + обновления версий) для экосистем `bun` и `gradle` — это самое
   дешёвое раннее предупреждение ровно про те классы advisories, что перечислены выше.
2. **Закрепить действия CI на SHA коммитов.** Теги мажорных версий удобны, но изменяемы; SHA — нет.
3. **Закрепить `bun-version`** в обоих workflow (например, `1.4.x`), чтобы релиз тулчейна не менял то, что
   CI измеряет, за одну ночь.
4. **Рассмотреть верификацию зависимостей Gradle** (`--write-verification-metadata sha256`), когда
   каталог версий устоится; это зеркальное отражение `distributionSha256Sum` для самих артефактов.
5. **Перепроверять advisories по netty** каждый раз, когда PacketEvents выпускает версию с более новым
   пином netty-all, и ещё раз, когда API Folia отойдёт от log4j 2.26.0 / guava 33.6.0.
