# md-to-tests

Сервис генерации тест-кейсов в формате Markdown по документу-требованию (ТЗ или OpenAPI-описание в Markdown).
Сервис принимает Markdown текстом или ссылку на `.md`, который отдал сервис [pdf-to-md](../pdf-to-md),
отправляет документ в LLM через [OpenRouter](https://openrouter.ai), проверяет ответ по строгим правилам
и сохраняет результат в виде отдельных файлов.

Подробнее о запуске всей системы в Docker — в [DOCKER.md](../DOCKER.md). Общее описание проекта — в [README.md](../README.md).

## Содержание

- [Возможности](#возможности)
- [Как работает генерация](#как-работает-генерация)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Конфигурация](#конфигурация)
- [API](#api)
- [Формат тест-кейса и правила валидации](#формат-тест-кейса-и-правила-валидации)
- [Файлы и хранилище](#файлы-и-хранилище)
- [База данных](#база-данных)
- [Интеграция с pdf-to-md](#интеграция-с-pdf-to-md)
- [Безопасность](#безопасность)
- [Архитектура и пакеты](#архитектура-и-пакеты)
- [Разработка](#разработка)
- [Ограничения](#ограничения)

## Возможности

- `POST /api/generate` — генерация тест-кейсов из Markdown (поле `markdown`) или из ссылки на Markdown (поле `pdfToMdUrl`).
- `POST /api/generate/{taskId}/answers` — ответы пользователя на уточнения; файлы прогона обновляются.
- Один тест-кейс — один файл `<префикс><ID>_<slug>.md`, плюс `_index.md` (оглавление) и `_clarifications.md` (уточнения, если они есть).
- `GET /api/files/{filename}` — скачивание файла в `text/markdown; charset=UTF-8`.
- `GET /actuator/health` — проверка состояния сервиса.
- Строгая проверка ответа модели: JSON-обёртка, ключи, допустимые значения полей, исправление или отбрасывание некорректных элементов.
- Санитизация домыслов: конкретные URL, HTTP-коды, поля, числа и цитаты, которых нет в документе, вырезаются из тест-кейсов.
- Атомарная запись файлов: при ошибке каталог возвращается в исходное состояние.
- Защита от path traversal и SSRF при скачивании Markdown.
- Сессии уточнений в памяти с ограничениями по времени жизни, количеству и размеру документа.
- Снимок каждого прогона записывается в PostgreSQL (схема `qa`).

## Как работает генерация

1. **Источник Markdown.** Если в запросе непустое поле `markdown`, используется оно. Иначе по `pdfToMdUrl` скачивается документ из pdf-to-md. Если не передано ни одно из полей, ответ — `400`.
2. **Запрос к LLM.** Системный промпт — `app.prompts.generate`, в котором `{{schema}}` заменён на JSON-схему `app.prompts.json-schema`. В пользовательский текст добавляется раздел «Разрешённые конкретные значения»: URL, HTTP-методы, коды, поля и числа, найденные в документе, а также производные числа (×24, ×7, ×60).
3. **Проверка ответа.** Снимается обёртка ` ```json `, JSON парсится, обязательны ключи `testCases` и `clarifications`. Некорректные тест-кейсы исправляются или отбрасываются (подробности — в [правилах валидации](#формат-тест-кейса-и-правила-валидации)). Если все тест-кейсы отброшены, ответ — `502`.
4. **Санитизация по документу.** Конкретика в `expectedResult` и шагах, которой нет в документе, блоке требования или ответах пользователя, заменяется нейтральной формулировкой. Для такой замены создаётся уточнение типа `UNGROUNDED_ASSUMPTION`.
5. **Статус прогона.** `NEEDS_CLARIFICATION`, если есть уточнение типа `MISSING_REQUIREMENT` (или без типа). Иначе `DONE`. Уточнения `UNGROUNDED_ASSUMPTION` статус не меняют.
6. **Запись файлов.** Файлы тест-кейсов, при необходимости `_clarifications.md` и `_index.md` пишутся атомарно. Файлы создаются при любом статусе.
7. **Сессия и БД.** При статусе `NEEDS_CLARIFICATION` сохраняется сессия в памяти (для ответов на уточнения). Снимок прогона записывается в таблицы схемы `qa`.

Ответ на уточнения (`/answers`) проходит те же шаги, но LLM получает исходный документ, текущие тест-кейсы и ответы пользователя. Конкретика из ответов не вырезается.

## Требования

- Java 21
- Maven 3.8+
- Ключ API OpenRouter (переменная `OPENROUTER_API_KEY`). Без ключа приложение не стартует.
- PostgreSQL с применённой схемой `qa` (DDL — файл `../db/init/01-schema.sql`). Без БД каждая успешная генерация завершится ошибкой `500`.
- Сервис pdf-to-md — только для режима `pdfToMdUrl`. Для режима `markdown` он не нужен.

## Быстрый старт

Сборка и запуск из каталога `md-to-test`.

PowerShell:

```powershell
$env:OPENROUTER_API_KEY = "sk-or-v1-..."
$env:DB_URL = "jdbc:postgresql://localhost:5432/qa"
$env:DB_USER = "qa_app"
$env:DB_PASSWORD = "<пароль>"
mvn spring-boot:run
```

bash:

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
export DB_URL="jdbc:postgresql://localhost:5432/qa"
export DB_USER="qa_app"
export DB_PASSWORD="<пароль>"
mvn spring-boot:run
```

После старта сервис доступен на `http://localhost:8082`. Проверка:

PowerShell:

```powershell
curl.exe http://localhost:8082/actuator/health
```

bash:

```bash
curl http://localhost:8082/actuator/health
```

Ожидаемый ответ: `{"status":"UP"}`. Если БД недоступна, статус будет `DOWN` (Actuator включает индикатор БД по умолчанию).

Пример генерации из raw Markdown (PowerShell):

```powershell
curl.exe -X POST http://localhost:8082/api/generate `
  -H "Content-Type: application/json" `
  -d '{"markdown":"# ТЗ\n\n## REQ-001 Логин\nПользователь входит по email и паролю.\n"}'
```

Тот же запрос в bash:

```bash
curl -X POST http://localhost:8082/api/generate \
  -H "Content-Type: application/json" \
  -d '{"markdown":"# ТЗ\n\n## REQ-001 Логин\nПользователь входит по email и паролю.\n"}'
```

## Конфигурация

Настройки находятся в `src/main/resources/application.yml`. Значения по умолчанию ниже — из этого файла. Переменные окружения подставляются через синтаксис `${ИМЯ:значение_по_умолчанию}`.

### Ключи и переменные окружения

| Ключ | Значение по умолчанию | Переменная окружения | Назначение |
|---|---|---|---|
| `server.port` | `8082` | — | Порт HTTP-сервера |
| `spring.application.name` | `md-to-tests` | — | Имя приложения |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/qa` | `DB_URL` | JDBC-адрес PostgreSQL |
| `spring.datasource.username` | `qa_app` | `DB_USER` | Пользователь БД |
| `spring.datasource.password` | пусто | `DB_PASSWORD` | Пароль БД |
| `app.storage.directory` | `${user.dir}/data/tests` | — | Каталог с выходными файлами (создаётся при старте) |
| `app.storage.base-url` | `http://localhost:8082` | `APP_STORAGE_BASE_URL` | Основа для `downloadUrl` в ответах |
| `app.pdf-to-md.base-url` | `http://localhost:8080` | `APP_PDF_TO_MD_BASE_URL` | Адрес pdf-to-md; хост берётся в allowlist по умолчанию |
| `app.pdf-to-md.connect-timeout-seconds` | `10` | — | Таймаут соединения с pdf-to-md, сек |
| `app.pdf-to-md.read-timeout-seconds` | `600` | — | Таймаут чтения ответа pdf-to-md, сек |
| `app.pdf-to-md.allowed-hosts` | `localhost` | `APP_PDF_TO_MD_ALLOWED_HOSTS` | Хосты, с которых разрешено скачивать Markdown (через запятую) |
| `app.sessions.ttl-minutes` | `120` | — | Время жизни сессии уточнений, мин |
| `app.sessions.max-count` | `1000` | — | Максимум одновременно хранимых сессий |
| `app.sessions.max-markdown-chars` | `1000000` | — | Максимум символов документа в сессии |
| `app.prompts.json-schema` | задан в yml | — | JSON-схема ответа, подставляется в `{{schema}}` |
| `app.prompts.generate` | задан в yml | — | Системный промпт первичной генерации |
| `app.prompts.answers` | задан в yml | — | Системный промпт обработки ответов |
| `openrouter.base-url` | `https://openrouter.ai/api/v1` | — | Базовый URL API OpenRouter |
| `openrouter.api-key` | пусто (обязателен) | `OPENROUTER_API_KEY` | Ключ API OpenRouter |
| `openrouter.model` | `anthropic/claude-haiku-5.5` | — | Идентификатор модели |
| `openrouter.max-output-tokens` | `50000` | — | Лимит токенов ответа (`max_tokens`) |
| `openrouter.timeout-seconds` | `600` | — | Таймаут чтения ответа OpenRouter, сек |
| `openrouter.connect-timeout-seconds` | `30` | — | Таймаут соединения с OpenRouter, сек |
| `openrouter.referer` | `http://localhost:8082` | — | Заголовок `HTTP-Referer` |
| `openrouter.title` | `md-to-tests` | — | Заголовок `X-Title` |
| `management.endpoints.web.exposure.include` | `health,info` | — | Включённые эндпоинты Actuator |
| `logging.level.application` | `DEBUG` | — | Уровень логов пакета `application` |

Переменные окружения сервис читает только через перечисленные ключи. Другие переменные из `docker-compose.yml` (например, `APP_DOWNLOAD_ALLOWED_HOSTS`, `APP_TARGET_ALLOWED_HOSTS`) относятся к другим сервисам.

### Проверка при старте

Приложение не поднимется, если:

- `openrouter.api-key` пустой;
- `openrouter.base-url` или `app.pdf-to-md.base-url` не начинаются с `http://` или `https://`;
- не заданы тексты промптов `app.prompts.*`;
- лимиты `*-timeout-seconds`, `ttl-minutes`, `max-count`, `max-markdown-chars`, `max-output-tokens` меньше 1;
- не удаётся создать каталог `app.storage.directory`.

## API

Все эндпоинты возвращают JSON, кроме скачивания файлов. Ошибки имеют единый формат:

```json
{
  "timestamp": "2026-11-04T00:00:00Z",
  "status": 400,
  "message": "Нужно передать либо markdown, либо pdfToMdUrl"
}
```

Поле `timestamp` — строка ISO-8601. Поле `status` совпадает с HTTP-статусом ответа.

Сервис не требует аутентификации и не настраивает CORS.

### POST /api/generate

Первичная генерация тест-кейсов. Тело запроса — одно из двух полей: `markdown` или `pdfToMdUrl`. Если переданы оба, используется `markdown`.

Тело с raw Markdown:

```json
{
  "markdown": "# ТЗ\n\n## REQ-001 Логин\nПользователь входит по email и паролю.\n"
}
```

Тело со ссылкой на Markdown из pdf-to-md:

```json
{
  "pdfToMdUrl": "http://localhost:8080/api/files/abc123_document.md",
  "filename": "document.pdf"
}
```

| Поле | Тип | Обязательно | Описание |
|---|---|---|---|
| `markdown` | строка | нет (нужно `markdown` или `pdfToMdUrl`) | Markdown-текст документа |
| `pdfToMdUrl` | строка | нет | Абсолютный `http`/`https`-URL на `.md`; хост должен быть в allowlist |
| `filename` | строка | нет | Имя исходного документа; из него строится префикс файлов (см. [Файлы и хранилище](#файлы-и-хранилище)) |

Успешный ответ `200 OK`:

```json
{
  "taskId": "d1c8e4a1-5b7a-4f0e-9a52-1c2d3e4f5a6b",
  "status": "NEEDS_CLARIFICATION",
  "testCases": [
    {
      "id": "TC-UI-001",
      "type": "UI",
      "title": "Login user",
      "requirementRef": "REQ-001",
      "preconditions": ["Пользователь зарегистрирован"],
      "steps": ["Открыть страницу входа", "Ввести email и пароль", "Нажать кнопку Войти"],
      "expectedResult": "Пользователь авторизован",
      "priority": "P1"
    }
  ],
  "clarifications": [
    {
      "requirementRef": "REQ-001",
      "question": "Каковы требования к паролю?",
      "reason": "В документе не указаны требования к паролю",
      "type": "MISSING_REQUIREMENT"
    }
  ],
  "files": [
    {
      "id": "TC-UI-001",
      "filename": "TC-UI-001_login_user.md",
      "downloadUrl": "http://localhost:8082/api/files/TC-UI-001_login_user.md"
    },
    {
      "id": "_clarifications",
      "filename": "_clarifications.md",
      "downloadUrl": "http://localhost:8082/api/files/_clarifications.md"
    },
    {
      "id": "_index",
      "filename": "_index.md",
      "downloadUrl": "http://localhost:8082/api/files/_index.md"
    }
  ]
}
```

Значения `status`: `DONE` или `NEEDS_CLARIFICATION`. Поле `clarifications` может содержать элементы с `type: "UNGROUNDED_ASSUMPTION"` — это вырезанные домыслы модели. В файлы они не выводятся, и на статус прогона не влияют.

Файл `_clarifications.md` создаётся только при наличии уточнений типа `MISSING_REQUIREMENT`. Файл `_index.md` создаётся, если есть хотя бы один тест-кейс.

Если `status` равен `NEEDS_CLARIFICATION`, сохраните `taskId` — он нужен для ответов на уточнения. Сессия создаётся только в этом случае.

Ошибки:

| Код | Когда |
|---|---|
| `400` | Не передано ни `markdown`, ни `pdfToMdUrl`; `pdfToMdUrl` не `http`/`https` или некорректен; хост не в allowlist; документ длиннее `max-markdown-chars` при статусе `NEEDS_CLARIFICATION` (файлы в этом случае не записываются) |
| `502` | Ошибка OpenRouter (HTTP-ошибка, таймаут, пустой ответ); ответ модели не JSON или без ключей `testCases`/`clarifications`; все тест-кейсы ответа отклонены; ошибка скачивания или пустой Markdown из pdf-to-md |
| `500` | Ошибка записи файлов (`Ошибка хранилища: ...`), ошибка БД, прочие необработанные ошибки |

Пример ошибки `502`:

```json
{
  "timestamp": "2026-11-04T00:00:00Z",
  "status": 502,
  "message": "OpenRouter HTTP 401: ..."
}
```

Пример `400` при отсутствии источника:

```json
{
  "timestamp": "2026-11-04T00:00:00Z",
  "status": 400,
  "message": "Нужно передать либо markdown, либо pdfToMdUrl"
}
```

### POST /api/generate/{taskId}/answers

Ответы на уточнения по существующему прогону. Путь содержит `taskId` из ответа `POST /api/generate`.

Тело запроса:

```json
{
  "answers": [
    {
      "requirementRef": "REQ-001",
      "answer": "Пароль: минимум 8 символов, буквы и цифры"
    }
  ]
}
```

| Поле | Тип | Описание |
|---|---|---|
| `answers` | массив | Ответы пользователя; элементы не валидируются, пустой массив допустим, но всё равно вызывает LLM |
| `answers[].requirementRef` | строка | Требование, к которому относится ответ |
| `answers[].answer` | строка | Текст ответа |

Успешный ответ `200 OK` имеет тот же формат, что и `POST /api/generate`:

- `status: "DONE"` — сессия удаляется, дальнейшие ответы на этот `taskId` невозможны.
- `status: "NEEDS_CLARIFICATION"` — сессия сохраняется, можно ответить снова с тем же `taskId`.

Файлы, созданные для тех же `id`, перезаписываются под прежними именами. Новые файлы получают новые имена. Файлы прошлого прогона, которых нет в новом наборе, удаляются. Например, при `DONE` файл `_clarifications.md` удаляется.

Ошибки:

| Код | Когда |
|---|---|
| `400` | Сессия не найдена, истекла или уже закрыта статусом `DONE` |
| `502` | Ошибка OpenRouter или ответ модели не прошёл проверку |
| `500` | Ошибка записи файлов или БД, прочие необработанные ошибки |

Пример `400`:

```json
{
  "timestamp": "2026-11-04T00:00:00Z",
  "status": 400,
  "message": "Сессия не найдена или истекла"
}
```

### GET /api/files/{filename}

Скачивание сгенерированного файла. Ответ `200 OK` содержит:

- `Content-Type: text/markdown; charset=UTF-8`;
- `Content-Disposition: attachment; filename="<имя>"`;
- `Content-Length`, если размер файла определён.

Имя файла должно соответствовать шаблону `^[a-zA-Z0-9_\-]+\.md$`.

Пример:

PowerShell:

```powershell
curl.exe -OJ http://localhost:8082/api/files/TC-UI-001_login_user.md
```

bash:

```bash
curl -OJ http://localhost:8082/api/files/TC-UI-001_login_user.md
```

Ошибки:

| Код | Когда |
|---|---|
| `400` | Имя не соответствует шаблону; путь выходит за пределы каталога; файл не найден (сообщение `Файл не найден: <имя>`) |
| `500` | Не удалось построить URL файла (`Ошибка хранилища: ...`) |

### GET /actuator/health

Состояние сервиса. Включает индикатор БД, который Spring Boot добавляет при наличии `DataSource`.

```json
{
  "status": "UP"
}
```

При недоступной БД статус `DOWN`, HTTP-код `503` — стандартное поведение Actuator.

### GET /actuator/info

Информация о приложении (включён в `management.endpoints.web.exposure.include`). Содержимое по умолчанию пустое, если не настроены дополнительные источники.

## Формат тест-кейса и правила валидации

Модель должна вернуть JSON вида:

```json
{
  "testCases": [
    {
      "id": "TC-UI-001",
      "type": "UI",
      "title": "Login user",
      "requirementRef": "REQ-001",
      "preconditions": ["Предусловие"],
      "steps": ["Шаг 1", "Шаг 2"],
      "expectedResult": "Ожидаемый результат",
      "priority": "P1"
    }
  ],
  "clarifications": [
    {
      "requirementRef": "REQ-001",
      "question": "Вопрос",
      "reason": "Причина",
      "type": "MISSING_REQUIREMENT"
    }
  ]
}
```

Что проверяет `SyncGenerationService` и что делает при нарушении:

| Поле | Правило | При нарушении |
|---|---|---|
| `id` | Формат `TC-<TYPE>-<NNN>`, где `<TYPE>` ∈ {`UI`, `API`, `E2E`, `NEG`, `NEGATIVE`, `SEC`, `SECURITY`, `PERF`, `PERFORMANCE`}, номер от 3 цифр; уникален в ответе | Заменяется на новый `TC-<TYPE>-<NNN>` |
| `type` | Одно из `UI`, `API`, `E2E`, `NEGATIVE`, `SECURITY`, `PERFORMANCE` | Заменяется на `UI` |
| `title` | Непустой; длиннее 200 символов обрезается | Пустой — тест-кейс пропускается |
| `requirementRef` | Непустая строка до 100 символов без переносов строк, содержащая букву или цифру | Заменяется на `N/A` |
| `preconditions` | Массив строк; `null` превращается в пустой массив | — |
| `steps` | Массив строк; пустые строки удаляются | Если шагов не осталось — тест-кейс пропускается |
| `expectedResult` | Непустая строка | Пустой — тест-кейс пропускается |
| `priority` | Одно из `P0`, `P1`, `P2`, `P3` | Заменяется на `P2` |
| `clarifications[].requirementRef` | Непустой | Уточнение пропускается |
| `clarifications[].question` | Непустой | Уточнение пропускается |
| `clarifications[].type` | Если отсутствует — `MISSING_REQUIREMENT` | — |

Дополнительно:

- Если JSON обёрнут в ` ```json ... ``` `, обёртка снимается перед разбором.
- Если в ответе нет ключей `testCases` или `clarifications`, либо JSON невалиден, ответ — `502`.
- Если все тест-кейсы ответа отклонены, ответ — `502`.
- Уточнения одного `requirementRef` объединяются в одно. Основным выбирается уточнение типа `MISSING_REQUIREMENT`. Остальные вопросы попадают в `reason` с префиксом «Дополнительные детали».

Санитизация по документу (`SyncGenerationService`):

- Проверяются URL, HTTP-коды, snake_case-поля, числа от трёх цифр, параметры `?name=value` и цитаты в UI-тест-кейсах. Значение обосновано, если оно встречается в документе, в блоке требования (раздел Markdown с заголовком, содержащим `requirementRef`) или в ответах пользователя.
- Необоснованные фрагменты заменяются нейтральными формулировками (например, `[значение из ответа на уточнение]`, `Поведение соответствует требованиям (см. уточнение)`).
- Шаги NEGATIVE-тест-кейсов не санитизируются: числа в них считаются тестовыми данными.
- При первичной генерации каждый вырезанный фрагмент порождает одно уточнение типа `UNGROUNDED_ASSUMPTION` на `requirementRef`. Оно попадает в JSON-ответ, но в файлы не выводится и статус не меняет.

## Файлы и хранилище

Каталог хранения — `app.storage.directory`, по умолчанию `<рабочая директория>/data/tests`. Создаётся при старте.

Имена файлов:

- тест-кейс: `<префикс><ID>_<slug>.md`, например `TC-UI-001_login_user.md`;
- индекс: `<префикс>_index.md`, например `document__index.md`;
- уточнения: `<префикс>_clarifications.md`.

Префикс строится из поля `filename` запроса: расширение отбрасывается, символы вне `[a-zA-Z0-9_-]` заменяются на `_`, имя обрезается до 40 символов, добавляется `_`. Например, `document.pdf` даёт префикс `document_`. Без `filename` префикс пуст.

`slug` строится из заголовка: нижний регистр, только латинские буквы и цифры, остальное заменяется на `_`, длина не больше 60 символов. Кириллица в slug не сохраняется, поэтому заголовок только на русском даёт имя вида `TC-UI-001_test_case.md`.

Коллизии имён: если имя занято файлом другого прогона, добавляется суффикс `_2`, `_3` и т. д. Существующие файлы не перезаписываются при первичной генерации.

Атомарность записи (`TestStorageService`):

1. Новые файлы пишутся во временный каталог `.staging-*` внутри каталога хранения, то есть на той же файловой системе.
2. Существующие файлы с теми же именами (и файлы на удаление) переносятся в резервную подкаталог `.backup` внутри staging.
3. Новые файлы переносятся в каталог хранения через `ATOMIC_MOVE`. Если файловая система не поддерживает атомарный перенос, используется обычный перенос с заменой.
4. При ошибке установленные файлы удаляются, резервные копии возвращаются на место. Временный каталог удаляется, кроме случая неполного отката: тогда он сохраняется, а в лог пишется ошибка.

## База данных

Сервис использует PostgreSQL через `JdbcTemplate` (`GenerationRepository`). Схема `qa` и таблицы создаются скриптом `../db/init/01-schema.sql`, которого в самом модуле нет. Миграций (Flyway/Liquibase) нет.

Что пишется при каждом `POST /api/generate` и `POST /api/generate/{taskId}/answers`:

- `qa.generation_runs` — строка прогона (`id` = `taskId`, статус, префикс, исходный Markdown); при повторе ключа обновляются статус, префикс, Markdown и `updated_at`;
- `qa.test_cases` — тест-кейсы прогона с именами файлов;
- `qa.clarifications` — уточнения прогона.

Прогон перезаписывается целиком: старые строки `test_cases` и `clarifications` удаляются и вставляются заново в одной транзакции.

Чтения из БД в приложении нет: данные только записываются. Запись выполняется после записи файлов, поэтому при ошибке БД файлы остаются на диске, а клиент получает `500`.

## Интеграция с pdf-to-md

Сервис pdf-to-md преобразует PDF в Markdown и отдаёт ссылку на `.md`. Сценарии использования:

**Сценарий 1. PDF → pdf-to-md → md-to-tests.** Ответ pdf-to-md содержит `downloadUrl` файла. Его передайте в `pdfToMdUrl`:

PowerShell:

```powershell
curl.exe -X POST http://localhost:8082/api/generate `
  -H "Content-Type: application/json" `
  -d '{"pdfToMdUrl":"http://localhost:8080/api/files/uuid_document.md","filename":"document.pdf"}'
```

bash:

```bash
curl -X POST http://localhost:8082/api/generate \
  -H "Content-Type: application/json" \
  -d '{"pdfToMdUrl":"http://localhost:8080/api/files/uuid_document.md","filename":"document.pdf"}'
```

**Сценарий 2. Markdown напрямую.** Без pdf-to-md: передайте текст в `markdown`.

**Сценарий 3. Ссылка на Markdown с другого хоста.** Добавьте хост в `APP_PDF_TO_MD_ALLOWED_HOSTS` (или в `app.pdf-to-md.allowed-hosts`). Иначе `pdfToMdUrl` вернёт `400` «Хост не разрешён».

Как сервис обращается к pdf-to-md:

- `app.pdf-to-md.base-url` используется только для выбора хоста по умолчанию, когда `allowed-hosts` пуст.
- Запросы выполняются по абсолютному URL из `pdfToMdUrl`, а не относительно `base-url`.

## Безопасность

- **Path traversal.** Имена файлов проверяются регулярным выражением `^[a-zA-Z0-9_\-]+\.md$` в `TestStorageService` (запись, существование, чтение). Дополнительно путь после `normalize()` должен оставаться внутри каталога хранения. Имя вида `../x.md` не проходит проверку.
- **SSRF при скачивании Markdown.** `PdfToMdClient` принимает только схемы `http` и `https` и только хосты из allowlist (сравнение без учёта регистра). Порт и путь не ограничиваются. Если `allowed-hosts` пуст, разрешён хост из `app.pdf-to-md.base-url`.
- **Атомарная запись.** Описана в разделе [Файлы и хранилище](#файлы-и-хранилище): при сбое каталог возвращается в исходное состояние.
- **Заголовок Content-Disposition.** Имя файла берётся из проверенного имени, поэтому не может содержать кавычек и переносов строк.
- **Ключ OpenRouter.** Передаётся в заголовке `Authorization` и не выводится в ответах API. Не храните его в `application.yml`: используйте переменную `OPENROUTER_API_KEY`.
- **Аутентификации нет.** Любой клиент, которому доступен порт 8082, может генерировать тест-кейсы и скачивать файлы. В Docker-конфигурации порты публикуются только на `127.0.0.1`.
- **Логи.** На уровне DEBUG (включён для пакета `application`) в лог пишутся usage OpenRouter и результаты скачивания. При ошибке разбора JSON в ERROR-лог попадает весь ответ модели.

## Архитектура и пакеты

```
Клиент ──► GenerationController ──► PdfToMdClient ──► pdf-to-md
              │                    (SSRF-allowlist)
              ├──► SyncGenerationService ──► OpenRouterClient ──► OpenRouter (LLM)
              │         (промпты, JSON, валидация, санитизация)
              ├──► TestCaseMarkdownRenderer  (рендер MD)
              ├──► TestStorageService        (атомарная запись в data/tests)
              ├──► SessionStore              (сессии уточнений в памяти)
              └──► GenerationRepository      (запись в PostgreSQL, схема qa)

FileController ──► TestStorageService   (GET /api/files/{filename})
GlobalExceptionHandler                  (маппинг исключений в HTTP-статусы)
```

| Пакет | Классы | Ответственность |
|---|---|---|
| `application` | `MdToTestsApplication` | Точка входа Spring Boot |
| `application.config` | `AppProperties`, `OpenRouterProperties`, `HttpConfig` | Настройки (`@ConfigurationProperties`) и бины `RestClient` с таймаутами |
| `application.controller` | `GenerationController`, `FileController` | REST-эндпоинты |
| `application.dto` | `Dtos`, `ErrorResponse` | Record-типы запросов, ответов и типов уточнений |
| `application.exception` | `GenerationException`, `PdfToMdClientException`, `StorageException`, `GlobalExceptionHandler` | Доменные исключения и маппинг в HTTP |
| `application.repository` | `GenerationRepository` | Запись прогона в БД |
| `application.service` | `SyncGenerationService`, `OpenRouterClient`, `PdfToMdClient`, `TestCaseMarkdownRenderer`, `TestStorageService`, `SessionStore` | Бизнес-логика, LLM, скачивание, рендер, хранилище, сессии |

## Разработка

Сборка и тесты (из каталога `md-to-test`):

PowerShell и bash:

```bash
mvn clean verify
```

Только тесты:

```bash
mvn test
```

Сборка исполняемого JAR без тестов:

```bash
mvn -DskipTests package
```

Запуск с горячей перезагрузкой не настроен; перезапускайте процесс после изменений.

Тесты:

- `SyncGenerationServiceTest` — разбор ответа модели, валидация, поведение при пустых полях, уточнения без типа (мок `OpenRouterClient`).
- `TestStorageServiceTest` — атомарная замена, удаление, отказ при небезопасных именах, уникализация. Один тест (`saveAll_duplicateNames_throwIllegalArgument`) отключён аннотацией `@Disabled`.

Lombok подключён как `optional` и требует поддержки обработки аннотаций в IDE.

Добавление эндпоинта:

1. Записи запроса и ответа добавьте в `application.dto.Dtos`.
2. Логику — в соответствующий сервис из `application.service`.
3. Эндпоинт — в контроллер из `application.controller`.
4. Новые ошибки — доменные исключения в `application.exception` и обработчик в `GlobalExceptionHandler`.

Docker: сборка в два этапа (`maven:3.9-eclipse-temurin-21` и `eclipse-temurin:21-jre-jammy`), запуск от пользователя `app`, каталог `/app/data` создаётся для данных. Подробности — в [DOCKER.md](../DOCKER.md).

## Ограничения

- **Сессии в памяти.** Сессии уточнений не сохраняются на диск и теряются при перезапуске. Один инстанс хранит свои сессии, горизонтальное масштабирование без общего хранилища сессий невозможно.
- **Вытеснение сессий.** При заполнении `max-count` удаляется самая старая по времени создания сессия, даже если она была перезаписана тем же `taskId`.
- **Параллельные ответы.** Два одновременных ответа на один `taskId` не синхронизируются, результат последнего перезаписывает предыдущий.
- **Нет отката БД.** Файлы записываются до записи в БД. Если БД недоступна, файлы остаются, а клиент получает `500`. Повтор запроса создаст новый `taskId` и новые файлы.
- **Ошибки формата запроса.** Невалидный JSON в теле запроса, по анализу кода, попадает в обработчик `Exception` и возвращает `500`, а не `400`. Проверено анализом кода, не запуском.
- **Размер документа.** Лимит `max-markdown-chars` применяется только к прогонам, которые требуют уточнений. Для остальных явного лимита нет.
- **Модель.** Нужна модель, поддерживающая `response_format: json_object`. Если OpenRouter или модель его не поддерживает, ответ приходит не в JSON и сервис возвращает `502`. Значение по умолчанию класса `openrouter/free` отличается от значения в `application.yml`; действует значение из yml.
- **Redirect в pdf-to-md.** Ответы на `GET` следуют по редиректам (поведение `SimpleClientHttpRequestFactory`), а адрес редиректа allowlist не проверяет. Это сужает защиту SSRF.
- **Имена файлов.** Для русских заголовков slug не содержит кириллицы, поэтому файлы получают имена вида `TC-UI-001_test_case.md`.
- **Неиспользуемый код.** `TestCaseMarkdownRenderer.renderIndex` (помечен `@deprecated`), перегрузки `SessionStore.put(...)` без карты файлов, метод `Dtos.GenerateRequest.hasFilename()` и конструктор `Clarification(requirementRef, question, reason)` внутри модуля не используются. Перед удалением проверьте внешних потребителей.
- **Параметр `isStep`/`isNegative`.** В `SyncGenerationService.sanitizeText` эти параметры не влияют на результат; различие шаг/ожидаемый результат для NEGATIVE обеспечивается вызывающим кодом.
- **Тестовые данные.** Отключённый тест `saveAll_duplicateNames_throwIllegalArgument` ожидает исключение, которого текущая реализация не бросает (имена уникализируются). Поведение документировано в `saveAll`.
