# md-to-tests

Сервис генерации тест-кейсов в формате Markdown по документу-требованию (ТЗ или OpenAPI-описание в Markdown).
Сервис принимает Markdown текстом или ссылку на `.md`, который отдал сервис [pdf-to-md](../pdf-to-md),
отправляет документ в LLM через [OpenRouter](https://openrouter.ai), проверяет ответ по строгим правилам
и сохраняет результат как отдельные файлы в PostgreSQL (`qa.artifacts`). Диск для файлов не используется.

Подробнее о запуске всей системы в Docker — в [DOCKER.md](../DOCKER.md). Общее описание проекта — в [README.md](../README.md).
Контракт хранения (артефакты, сессии, журнал) — в [docs/storage-contract.md](../docs/storage-contract.md).

## Содержание

- [Возможности](#возможности)
- [Как работает генерация](#как-работает-генерация)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Конфигурация](#конфигурация)
- [API](#api)
- [Формат тест-кейса и правила валидации](#формат-тест-кейса-и-правила-валидации)
- [Файлы и хранилище](#файлы-и-хранилище)
- [Хранение данных](#хранение-данных)
- [База данных](#база-данных)
- [Интеграция с pdf-to-md](#интеграция-с-pdf-to-md)
- [Безопасность](#безопасность)
- [Архитектура и пакеты](#архитектура-и-пакеты)
- [Разработка](#разработка)
- [Ограничения](#ограничения)

## Возможности

- `POST /api/generate` — генерация тест-кейсов из Markdown (поле `markdown`) или из ссылки на Markdown (поле `pdfToMdUrl`).
- `GET /api/generate/{taskId}` — состояние сессии уточнений: статус, версия, раунд, история ответов, черновик.
- `POST /api/generate/{taskId}/answers` — ответы пользователя на уточнения с оптимистичной блокировкой по `expectedVersion`; файлы прогона обновляются.
- `PUT /api/generate/{taskId}/draft` — сохранение черновика ответов (сервер хранит его между перезагрузками страницы).
- `GET /api/journal`, `PUT /api/journal/{id}`, `DELETE /api/journal/{id}`, `DELETE /api/journal` — журнал действий пользователя в БД.
- Один тест-кейс — один файл `<префикс><ID>_<slug>.md`, плюс `_index.md` (оглавление) и `_clarifications.md` (уточнения, если они есть).
- `GET /api/files/{id}` — скачивание артефакта по UUID в `text/markdown; charset=UTF-8`.
- `GET /actuator/health` — проверка состояния сервиса.
- Строгая проверка ответа модели: JSON-обёртка, ключи, допустимые значения полей, исправление или отбрасывание некорректных элементов.
- Санитизация домыслов: конкретные URL, HTTP-коды, поля, числа и цитаты, которых нет в документе, вырезаются из тест-кейсов.
- Транзакционная запись: файлы прогона, снимок прогона и сессия пишутся в одной транзакции БД.
- Защита от SSRF при скачивании Markdown из pdf-to-md.
- Срок хранения артефактов, сессий и журнала — 30 дней; очистка при старте и каждые 6 часов.

## Как работает генерация

1. **Источник Markdown.** Если в запросе непустое поле `markdown`, используется оно. Иначе по `pdfToMdUrl` скачивается документ из pdf-to-md. Если не передано ни одно из полей, ответ — `400`.
2. **Запрос к LLM.** Системный промпт — `app.prompts.generate`, в котором `{{schema}}` заменён на JSON-схему `app.prompts.json-schema`. В пользовательский текст добавляется раздел «Разрешённые конкретные значения»: URL, HTTP-методы, коды, поля и числа, найденные в документе, а также производные числа (×24, ×7, ×60).
3. **Проверка ответа.** Снимается обёртка ` ```json `, JSON парсится, обязательны ключи `testCases` и `clarifications`. Некорректные тест-кейсы исправляются или отбрасываются (подробности — в [правилах валидации](#формат-тест-кейса-и-правила-валидации)). Если все тест-кейсы отброшены, ответ — `502`.
4. **Санитизация по документу.** Конкретика в `expectedResult` и шагах, которой нет в документе, блоке требования или ответах пользователя, заменяется нейтральной формулировкой. Для такой замены создаётся уточнение типа `UNGROUNDED_ASSUMPTION`.
5. **Статус прогона.** `NEEDS_CLARIFICATION`, если есть уточнение типа `MISSING_REQUIREMENT` (или без типа). Иначе `DONE`. Уточнения `UNGROUNDED_ASSUMPTION` статус не меняют.
6. **Запись в БД, одна транзакция.** Файлы прогона (`kind` = `test_case`, `markdown`, `index`) пишутся в `qa.artifacts`; снимок прогона — в `qa.generation_runs`, `qa.test_cases`, `qa.clarifications`; при статусе `NEEDS_CLARIFICATION` создаётся сессия в `qa.clarification_sessions`. Файлы создаются при любом статусе. При ошибке БД ничего не сохраняется.
7. **Ответ.** В `files[].downloadUrl` — адрес `{APP_STORAGE_BASE_URL}/api/files/{uuid}`. Поле `version` равно `1`.

Ответ на уточнения (`/answers`) проходит шаги 2–7, но с отличиями: ответы пишутся в `qa.clarification_answers` (только добавляются); модели передаются исходный документ, текущие тест-кейсы и накопленный набор ответов без дублей по паре `topicKey` + `question`; `version` и `round` увеличиваются; вся операция выполняется в одной транзакции с блокировкой строки сессии (подробности — в [Ограничениях](#ограничения)).

## Требования

- Java 21
- Maven 3.8+
- Ключ API OpenRouter (переменная `OPENROUTER_API_KEY`). Без ключа приложение не стартует.
- PostgreSQL со схемой `qa`. Для новой БД — `../db/init/01-schema.sql` (содержит таблицы артефактов, сессий и журнала). Для уже работающей БД — `../db/migrations/0002_artifacts_sessions_journal.sql` (идемпотентен, выдаёт права роли `qa_app`). Без БД каждая генерация, ответ на уточнения, скачивание файла и операция с журналом завершаются ошибкой `500`.
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

Настройки находятся в `src/main/resources/application.yml`. Значения по умолчанию ниже — из этого файла. Переменные окружения подставляются через синтаксис `${ИМЯ:значение_по_умолчанию}`. Значения по умолчанию в классах `config` могут отличаться (например, `app.pdf-to-md.read-timeout-seconds` — 120, `openrouter.model` — `openrouter/free`); действуют значения из `application.yml`.

### Ключи и переменные окружения

| Ключ | Значение по умолчанию | Переменная окружения | Назначение |
|---|---|---|---|
| `server.port` | `8082` | — | Порт HTTP-сервера |
| `spring.application.name` | `md-to-tests` | — | Имя приложения |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/qa` | `DB_URL` | JDBC-адрес PostgreSQL: артефакты, сессии, журнал, снимки прогонов |
| `spring.datasource.username` | `qa_app` | `DB_USER` | Пользователь БД |
| `spring.datasource.password` | пусто | `DB_PASSWORD` | Пароль БД |
| `app.storage.base-url` | `http://localhost:8082` | `APP_STORAGE_BASE_URL` | Основа для `downloadUrl` в ответах: `{base}/api/files/{uuid}` |
| `app.pdf-to-md.base-url` | `http://localhost:8080` | `APP_PDF_TO_MD_BASE_URL` | Адрес pdf-to-md; его хост попадает в allowlist, только если `allowed-hosts` пуст |
| `app.pdf-to-md.connect-timeout-seconds` | `10` | — | Таймаут соединения с pdf-to-md, сек |
| `app.pdf-to-md.read-timeout-seconds` | `600` | — | Таймаут чтения ответа pdf-to-md, сек |
| `app.pdf-to-md.allowed-hosts` | `localhost` | `APP_PDF_TO_MD_ALLOWED_HOSTS` | Хосты, с которых разрешено скачивать Markdown (через запятую) |
| `app.sessions.max-markdown-chars` | `1000000` | — | Максимум символов документа при создании сессии уточнений |
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

Явно через `${…}` в `application.yml` читаются только переменные из таблицы. Кроме них Spring Boot по relaxed binding подставляет переменные окружения, имена которых совпадают с ключами (например, `SERVER_PORT`, `OPENROUTER_MODEL`). Переменные `APP_DOWNLOAD_ALLOWED_HOSTS` и `APP_TARGET_ALLOWED_HOSTS` из `docker-compose.yml` относятся к test-runner и на md-to-tests не влияют.

Параметров сроков хранения (TTL) и лимита количества сессий в конфигурации нет: срок хранения фиксирован — 30 дней (см. [Хранение данных](#хранение-данных)).

### Проверка при старте

Приложение не поднимется, если:

- `openrouter.api-key` пустой;
- `openrouter.base-url`, `app.pdf-to-md.base-url` или `app.storage.base-url` не начинаются с `http://` или `https://`;
- не заданы тексты промптов `app.prompts.*`;
- лимиты `*-timeout-seconds`, `max-markdown-chars`, `max-output-tokens` меньше 1.

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
      "downloadUrl": "http://localhost:8082/api/files/3f2b8c1e-6a4d-4f7e-9c2a-8d1e5f0b7a31"
    },
    {
      "id": "_clarifications",
      "filename": "_clarifications.md",
      "downloadUrl": "http://localhost:8082/api/files/9a7c0d2e-1b3f-4e5a-8c6d-2f4e6a8b0c13"
    },
    {
      "id": "_index",
      "filename": "_index.md",
      "downloadUrl": "http://localhost:8082/api/files/c4e8f1a3-5d7b-4c9e-a2f6-6b8d0e2f4a57"
    }
  ],
  "version": 1
}
```

Значения `status`: `DONE` или `NEEDS_CLARIFICATION`. Поле `clarifications` может содержать элементы с `type: "UNGROUNDED_ASSUMPTION"` — это вырезанные домыслы модели. В файлы они не выводятся, и на статус прогона не влияют.

Файл `_clarifications.md` создаётся только при наличии уточнений типа `MISSING_REQUIREMENT`. Файл `_index.md` создаётся, если есть хотя бы один тест-кейс.

Сессия уточнений создаётся только при `status: "NEEDS_CLARIFICATION"`. Сохраните `taskId`: он нужен для `GET`, `PUT draft` и `POST answers`. При `DONE` сессии нет, и обращения к `/api/generate/{taskId}` вернут `400`.

Ошибки:

| Код | Когда |
|---|---|
| `400` | Не передано ни `markdown`, ни `pdfToMdUrl`; `pdfToMdUrl` не `http`/`https` или некорректен; хост не в allowlist; документ длиннее `max-markdown-chars` при статусе `NEEDS_CLARIFICATION` (в этом случае ничего не записывается) |
| `502` | Ошибка OpenRouter (HTTP-ошибка, таймаут, пустой ответ); ответ модели не JSON или без ключей `testCases`/`clarifications`; все тест-кейсы ответа отклонены; ошибка скачивания или пустой Markdown из pdf-to-md |
| `500` | Ошибка БД (транзакция откатывается, `taskId` не возвращается), прочие необработанные ошибки |

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

### GET /api/generate/{taskId}

Состояние сессии уточнений. Тело — последний ответ `POST` плюс поля сессии из базы данных:

```json
{
  "taskId": "d1c8e4a1-5b7a-4f0e-9a52-1c2d3e4f5a6b",
  "status": "NEEDS_CLARIFICATION",
  "version": 2,
  "round": 1,
  "testCases": [ "...как в последнем ответе POST" ],
  "clarifications": [ "...как в последнем ответе POST" ],
  "files": [ "...как в последнем ответе POST" ],
  "answers": [
    {
      "round": 1,
      "testCaseId": "TC-UI-001",
      "question": "Каковы требования к паролю?",
      "topicKey": "password-policy",
      "answer": "Минимум 8 символов, буквы и цифры",
      "answeredAt": "2026-11-05T10:00:00Z"
    }
  ],
  "draft": { "0": "черновик ответа на вопрос с индексом 0" },
  "expiresAt": "2026-12-05T10:00:00Z"
}
```

| Поле | Описание |
|---|---|
| `status`, `version`, `round` | Текущие значения сессии. `version` растёт на 1 при каждом успешном ответе на уточнения, `round` — на 1 при каждом `POST` |
| `testCases`, `clarifications`, `files` | Поля последнего ответа `POST` (в том же виде, что и в ответе генерации) |
| `answers` | Вся история ответов сессии без схлопывания дублей. Модели передаётся схлопнутый набор |
| `draft` | Черновик ответов, как его сохранил `PUT .../draft` |
| `expiresAt` | Срок хранения сессии (30 дней от последнего изменения) |

Ошибки:

| Код | Когда |
|---|---|
| `400` | Сессия не найдена, истекла или `taskId` не UUID (сообщение `Сессия не найдена или истекла`) |

Пример `400`:

```json
{
  "timestamp": "2026-11-04T00:00:00Z",
  "status": 400,
  "message": "Сессия не найдена или истекла"
}
```

### POST /api/generate/{taskId}/answers

Ответы пользователя на уточнения по существующей сессии. Путь содержит `taskId` из ответа `POST /api/generate`.

Тело запроса:

```json
{
  "answers": [
    {
      "question": "Каковы требования к паролю?",
      "answer": "Минимум 8 символов, буквы и цифры",
      "topicKey": "password-policy",
      "testCaseId": "TC-UI-001"
    }
  ],
  "expectedVersion": 1
}
```

| Поле | Тип | Описание |
|---|---|---|
| `answers` | массив | Ответы пользователя. Элементы с пустым или пробельным `answer` отбрасываются. Пустой массив допустим, но модель всё равно вызывается |
| `answers[].question` | строка | Вопрос, на который дан ответ |
| `answers[].answer` | строка | Текст ответа |
| `answers[].topicKey` | строка | Ключ темы; вместе с `question` задаёт уникальность ответа |
| `answers[].testCaseId` | строка | Тест-кейс, к которому относится ответ |
| `expectedVersion` | число | Необязательно. Версия, которую видел клиент. Если передана и не равна текущей `version`, ответ `409` |

Поведение при успешном запросе:

- Непустые ответы добавляются в историю (`qa.clarification_answers`) с `round` = текущий `round` + 1. Строки истории не изменяются и не удаляются до истечения срока сессии.
- Модели передаётся исходный документ, текущие тест-кейсы и накопленный набор ответов, в котором дубли по паре `topicKey` + `question` схлопнуты (побеждает последний ответ).
- `version` и `round` увеличиваются на 1 — даже если все ответы пустые.
- Файлы прогона обновляются: для того же `id` сохраняется прежнее имя, но выдаётся новый UUID; файлы прошлого набора, которых нет в новом, удаляются. Ссылки `downloadUrl` из предыдущего ответа после этого не работают — используйте адреса из последнего ответа.
- Статус прогона пересчитывается. При `DONE` сессия закрывается для дальнейших ответов; файл `_clarifications.md` удаляется.

Успешный ответ `200 OK` имеет тот же формат, что и `POST /api/generate`, и содержит уже увеличенную `version`:

```json
{
  "taskId": "d1c8e4a1-5b7a-4f0e-9a52-1c2d3e4f5a6b",
  "status": "DONE",
  "testCases": [ "..." ],
  "clarifications": [],
  "files": [ "..." ],
  "version": 2
}
```

Ошибки:

| Код | Когда |
|---|---|
| `400` | Сессия не найдена, истекла или `taskId` не UUID (`Сессия не найдена или истекла`); сессия уже закрыта статусом `DONE` (`Сессия уже завершена, ответы не принимаются`) |
| `409` | `expectedVersion` не совпадает с текущей версией: `Сессия уже изменена в другом окне. Обновите страницу.` |
| `502` | Ошибка OpenRouter или ответ модели не прошёл проверку; сессия и файлы не меняются |
| `500` | Ошибка БД, прочие необработанные ошибки |

Пример `409`:

```json
{
  "timestamp": "2026-11-05T10:05:00Z",
  "status": 409,
  "message": "Сессия уже изменена в другом окне. Обновите страницу."
}
```

### PUT /api/generate/{taskId}/draft

Сохраняет черновик ответов на уточнения. Черновик перезаписывается целиком, `version` не меняется.

Тело запроса:

```json
{
  "draft": { "0": "Минимум 8 символов" }
}
```

Ответ — `204 No Content`. Если сессия не найдена или истекла, — `400` (`Сессия не найдена или истекла`).

### GET /api/journal

Записи журнала с неистёкшим сроком, новые первыми, не более 500. Ответ `200 OK` — массив:

```json
[
  {
    "id": "5e0c7a2b-9d4f-4b1e-8a3c-7f6e5d4c3b2a",
    "type": "generate",
    "title": "ТЗ: вход в систему",
    "status": "NEEDS_CLARIFICATION",
    "taskId": "d1c8e4a1-5b7a-4f0e-9a52-1c2d3e4f5a6b",
    "sourceName": "document.pdf",
    "createdAt": "2026-11-05T07:00:00Z",
    "payload": { "files": [] }
  }
]
```

### PUT /api/journal/{id}

Создаёт или обновляет запись журнала с указанным UUID (upsert). Срок хранения записи продлевается.

Тело запроса:

```json
{
  "type": "generate",
  "title": "ТЗ: вход в систему",
  "status": "NEEDS_CLARIFICATION",
  "taskId": "d1c8e4a1-5b7a-4f0e-9a52-1c2d3e4f5a6b",
  "sourceName": "document.pdf",
  "createdAt": "2026-11-05T10:00:00+03:00",
  "payload": {
    "files": [
      { "label": "Отчёт", "url": "http://localhost:8082/api/files/9a7c0d2e-1b3f-4e5a-8c6d-2f4e6a8b0c13", "downloadName": "_clarifications.md" }
    ]
  }
}
```

| Поле | Правило |
|---|---|
| `type` | Обязательно: `convert`, `generate` или `run` |
| `title` | Обязательно, не пустое |
| `status`, `sourceName` | Строка или `null` |
| `taskId` | UUID или `null` |
| `createdAt` | Обязательно, ISO-8601 с часовым поясом (смещение обязательно) |
| `payload` | JSON-объект или `null` (тогда `{}`). Сервер его не разбирает |

Ответ `200 OK` — сохранённая запись в формате элемента `GET /api/journal`.

Ошибки `400`: некорректный `id` (не UUID), `type` не из списка, пустой `title`, `createdAt` не разбирается, `taskId` не UUID, `payload` не объект.

### DELETE /api/journal/{id}

Удаляет запись журнала. Ответ — `204 No Content`; отсутствие записи не считается ошибкой. Некорректный `id` — `400`.

### DELETE /api/journal

Очищает весь журнал. Ответ — `204 No Content`.

### GET /api/files/{id}

Скачивание артефакта по UUID. `id` — значение из `files[].downloadUrl` или из `payload.files` журнала. Файл берётся из `qa.artifacts`.

Ответ `200 OK` содержит:

- `Content-Type` из `content_type` артефакта (для Markdown — `text/markdown; charset=UTF-8`);
- `Content-Disposition: attachment; filename="<имя>"`, где имя — `filename` артефакта.

Пример:

PowerShell:

```powershell
curl.exe -OJ http://localhost:8082/api/files/3f2b8c1e-6a4d-4f7e-9c2a-8d1e5f0b7a31
```

bash:

```bash
curl -OJ http://localhost:8082/api/files/3f2b8c1e-6a4d-4f7e-9c2a-8d1e5f0b7a31
```

Ошибки:

| Код | Когда |
|---|---|
| `400` | `id` не UUID, артефакт не найден или срок хранения истёк. Сообщение одно и то же: `Файл не найден` |
| `500` | Ошибка БД |

Пример `400`:

```json
{
  "timestamp": "2026-11-05T10:00:00Z",
  "status": 400,
  "message": "Файл не найден"
}
```

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

### Ошибки запроса Spring MVC

Общие обработчики в `GlobalExceptionHandler` возвращают тело `ErrorResponse` (`timestamp`, `status`, `message` на русском) для ошибок самого фреймворка:

| Код | Когда |
|---|---|
| `400` | Не передан обязательный параметр или часть multipart-запроса; тело не читается (невалидный JSON или не соответствует схеме); значение параметра не приводится к нужному типу |
| `404` | Адрес не найден (`NoResourceFoundException`) |
| `405` | HTTP-метод не поддерживается для адреса |
| `415` | Content-Type запроса не поддерживается (для JSON-эндпоинтов нужен `application/json`) |

Любое другое необработанное исключение по-прежнему даёт `500` с сообщением «Внутренняя ошибка сервера».

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

- Обёртки ` ```json `, ` ``` ` и ` ```JSON ` снимаются перед разбором (регистр не учитывается); пробелы и переводы строк по краям игнорируются.
- Если в ответе нет ключей `testCases` или `clarifications`, либо JSON невалиден, ответ — `502`.
- Если все тест-кейсы ответа отклонены, ответ — `502`.
- Уточнения одного `requirementRef` объединяются в одно. Основным выбирается уточнение типа `MISSING_REQUIREMENT`. `reason` основного уточнения сохраняется; остальные вопросы добавляются к нему с префиксом «Дополнительные детали: …».
- Автогенерируемые id (`TC-<TYPE>-<NNN>`) не совпадают с id, которые модель вернула в этом же ответе; корректные id модели сохраняются.

Санитизация по документу (`SyncGenerationService`):

- Проверяются HTTP-коды, snake_case-поля, числа от трёх цифр, параметры `?name=value` (в том числе внутри абсолютных URL) и цитаты в UI-тест-кейсах. Абсолютные URL не вырезаются целиком: из них удаляются только необоснованные параметры запроса, хост и путь не меняются. Контекст HTTP-кода («Статус», «Ответ») распознаётся независимо от регистра, в том числе кириллицы. Значение обосновано, если оно встречается в документе, в блоке требования (раздел Markdown с заголовком, содержащим `requirementRef`) или в ответах пользователя. Число, полученное умножением на 24, 7 или 60 из обоснованного числа, тоже считается обоснованным.
- Необоснованные фрагменты удаляются или заменяются: число — на `[значение из ответа на уточнение]`, цитата — на `«…»`. Если ожидаемый результат вырезан полностью, ставится `Поведение соответствует требованиям (см. уточнение)`, пустой шаг — `(точное поведение уточняется)`.
- Пробел между текстом и URL сохраняется: «Открыть https://x» не превращается в «Открытьhttps://x».
- Шаги NEGATIVE-тест-кейсов не санитизируются: числа в них считаются тестовыми данными.
- При первичной генерации, если что-то вырезано, по каждому `requirementRef` добавляется одно уточнение типа `UNGROUNDED_ASSUMPTION` (все вырезанные фрагменты перечисляются в вопросе). Оно попадает в JSON-ответ, но в файлы не выводится и статус не меняет.

## Файлы и хранилище

Файлы не пишутся на диск. Каждый файл прогона — строка в таблице `qa.artifacts`; клиент получает его по UUID через `GET /api/files/{id}`.

Имена файлов:

- тест-кейс: `<префикс><ID>_<slug>.md`, например `TC-UI-001_login_user.md`;
- индекс: `<префикс>_index.md`, например `document_index.md`. Префикс уже оканчивается на `_`, поэтому второй `_` не добавляется; без префикса индекс называется `_index.md`;
- уточнения: `<префикс>_clarifications.md`.

Префикс строится из поля `filename` запроса: расширение отбрасывается, символы вне `[a-zA-Z0-9_-]` заменяются на `_`, имя обрезается до 40 символов, добавляется `_`. Например, `document.pdf` даёт префикс `document_`. Без `filename` префикс пуст.

`slug` строится из заголовка: нижний регистр, латинские буквы и цифры; любая последовательность других символов заменяется одним `_`; крайние `_` убираются; длина не больше 60 символов. Кириллица в slug не сохраняется, поэтому заголовок только на русском даёт имя вида `TC-UI-001_test_case.md`.

Коллизии имён: внутри одного набора файлов имя не повторяется — при занятом имени добавляется суффикс `_2`, `_3` и т. д. Имена разных прогонов не сравниваются: в `qa.artifacts` имя не уникально, и одно имя может быть у артефактов разных прогонов. Внутри прогона файл с тем же `filename` и `kind` заменяется, а не дублируется.

Скачивание: `GET /api/files/{id}`, где `id` — UUID артефакта. Имя файла не используется для поиска, поэтому обход путей невозможен (см. [Безопасность](#безопасность)).

Запись файлов прогона атомарна: все файлы одного набора (тест-кейсы, `_clarifications.md`, `_index.md`) и удаление файлов прошлого набора выполняются в одной транзакции вместе со снимком прогона и сессией. Частичной записи набора быть не может.

## Хранение данных

Все бизнес-данные сервиса хранятся в PostgreSQL. Схема описана в [docs/storage-contract.md](../docs/storage-contract.md), DDL — в `../db/init/01-schema.sql` и `../db/migrations/0002_artifacts_sessions_journal.sql`.

| Что | Таблица | Примечание |
|---|---|---|
| Файлы прогона | `qa.artifacts` | `kind`: `test_case` (тест-кейс), `index` (`_index.md`), `markdown` (`_clarifications.md`). `run_id` = `taskId` генерации, `owner_service` = `md-to-test`. Другие сервисы пишут также `pdf_input`, `enriched_case`, `enriched_index`, `html_dump` |
| Сессии уточнений | `qa.clarification_sessions` | Сессии генерации (`service = 'generate'`); переживают перезапуск сервиса |
| История ответов | `qa.clarification_answers` | Только добавление строк; удаляется каскадно вместе с сессией |
| Журнал | `qa.journal_entries` | Записи журнала пользователя; вместо `localStorage` браузера |
| Снимок прогона | `qa.generation_runs`, `qa.test_cases`, `qa.clarifications` | Перезаписывается целиком при каждом ответе на уточнения |

Срок хранения — 30 дней для всех записей (`expires_at`):

- артефакт, у которого истёк `expires_at`, не отдаётся (`400 Файл не найден`) и удаляется очисткой;
- сессия продлевается при каждом изменении (ответ на уточнения, черновик); при истечении ответы и черновик возвращают `400 Сессия не найдена или истекла`;
- запись журнала продлевается при каждом `PUT`; истёкшие записи не попадают в `GET /api/journal`.

Очистка по сроку выполняется классом `ExpirationCleaner`: при старте приложения (`ApplicationReadyEvent`) и затем каждые 6 часов. Удаляются артефакты этого сервиса (`owner_service = 'md-to-test'`), сессии генерации (`service = 'generate'`) и записи журнала с истёкшим сроком. Ошибка очистки пишется в лог с уровнем ERROR и не останавливает приложение.

Миграция старых данных: файлы из прежнего каталога `data/tests` и записи журнала из `localStorage` браузера не переносятся.

## База данных

Сервис использует PostgreSQL через `JdbcTemplate`. Репозитории:

- `ArtifactRepository` — `qa.artifacts`: замена файлов прогона в одной транзакции, чтение по UUID, очистка по сроку;
- `ClarificationSessionRepository` — `qa.clarification_sessions` и `qa.clarification_answers`: создание, чтение с блокировкой строки (`SELECT ... FOR UPDATE`), обновление версии и черновика, добавление ответов;
- `JournalRepository` — `qa.journal_entries`: upsert, выборка, удаление, очистка;
- `GenerationRepository` — снимок прогона в `qa.generation_runs`, `qa.test_cases`, `qa.clarifications`.

Схема `qa` создаётся скриптом `../db/init/01-schema.sql` (только для пустого тома БД). Для уже работающей БД применяется `../db/migrations/0002_artifacts_sessions_journal.sql` (идемпотентен, выдаёт права `qa_app` и `qa_agent`). Миграций на уровне сервиса (Flyway/Liquibase) нет.

Что пишется при каждом `POST /api/generate` и `POST /api/generate/{taskId}/answers`, — одна транзакция `TransactionTemplate`:

- `qa.artifacts` — файлы прогона;
- `qa.generation_runs`, `qa.test_cases`, `qa.clarifications` — снимок прогона (строки прогона перезаписываются целиком);
- `qa.clarification_sessions` — создаётся при `POST /api/generate` со статусом `NEEDS_CLARIFICATION`, обновляется при ответе;
- `qa.clarification_answers` — при ответе на уточнения.

Ответ на уточнения дополнительно читает историю ответов и сессию в той же транзакции.

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

- **Скачивание без обхода путей.** `GET /api/files/{id}` принимает только UUID; любое другое значение (например, `../x.md`) даёт `400 Файл не найден`. Файлы читаются из БД, с диска не читаются, поэтому path traversal невозможен.
- **SSRF при скачивании Markdown.** `PdfToMdClient` принимает только схемы `http` и `https` и только хосты из allowlist (сравнение без учёта регистра). Порт и путь не ограничиваются. Если `allowed-hosts` пуст, разрешён хост из `app.pdf-to-md.base-url`.
- **Редиректы pdf-to-md.** Автоматическое следование редиректам отключено (`HttpConfig`). Каждый ответ 3xx обрабатывается вручную: `Location` резолвится относительно текущего URL, и для него проверяются схема `http`/`https` и allowlist хоста. Переходов не больше 5. Нарушение или редирект без `Location` даёт `502` с сообщением о причине.
- **Транзакции.** Запись файлов, снимка прогона и сессии — в одной транзакции БД; при сбое откатывается всё. Описание поведения при сбоях — в [Ограничениях](#ограничения).
- **Заголовок Content-Disposition.** Строится через `ContentDisposition.attachment()`, а не конкатенацией строки; имя берётся из поля `filename` артефакта.
- **Ключ OpenRouter.** Передаётся в заголовке `Authorization` и не выводится в ответах API. Не храните его в `application.yml`: используйте переменную `OPENROUTER_API_KEY`.
- **Аутентификации нет.** Любой клиент, которому доступен порт 8082, может генерировать тест-кейсы, читать сессии по `taskId`, менять журнал и скачивать файлы по UUID. Защита от перебора — только непредсказуемость UUID. В Docker-конфигурации порты публикуются только на `127.0.0.1`.
- **Логи.** Для пакета `application` включён уровень DEBUG: в лог пишутся usage OpenRouter. URL и размер Markdown, скачанного из pdf-to-md, пишутся на уровне INFO. При ошибке разбора JSON в ERROR-лог попадает весь ответ модели.

## Архитектура и пакеты

```
Клиент ──► GenerationController ──► GenerationFlowService ──┬──► PdfToMdClient ──► pdf-to-md (SSRF-allowlist)
                                                           ├──► SyncGenerationService ──► OpenRouterClient ──► OpenRouter (LLM)
                                                           │      (промпты, JSON, валидация, санитизация)
                                                           ├──► TestCaseMarkdownRenderer  (рендер MD)
                                                           ├──► AnswerMerger              (дедупликация ответов, проверка версии)
                                                           ├──► ArtifactRepository        (qa.artifacts)
                                                           ├──► ClarificationSessionRepository (qa.clarification_sessions / _answers)
                                                           └──► GenerationRepository      (qa.generation_runs / test_cases / clarifications)

FileController ──► ArtifactRepository                      (GET /api/files/{id})
JournalController ──► JournalService ──► JournalRepository (qa.journal_entries)
ExpirationCleaner ──► ArtifactRepository, ClarificationSessionRepository, JournalRepository
                                                           (при старте и каждые 6 часов)
GlobalExceptionHandler                                     (маппинг исключений в HTTP-статусы)
```

| Пакет | Классы | Ответственность |
|---|---|---|
| `application` | `MdToTestsApplication` | Точка входа Spring Boot |
| `application.config` | `AppProperties`, `OpenRouterProperties`, `HttpConfig`, `SchedulingConfig` | Настройки (`@ConfigurationProperties`), бины `RestClient` с таймаутами, включение планировщика |
| `application.controller` | `GenerationController`, `FileController`, `JournalController` | REST-эндпоинты генерации, файлов и журнала |
| `application.dto` | `Dtos`, `ErrorResponse` | Record-типы запросов, ответов и типов уточнений; формат ошибки |
| `application.exception` | `GenerationException`, `PdfToMdClientException`, `ConflictException`, `StorageException`, `GlobalExceptionHandler` | Доменные исключения и маппинг в HTTP |
| `application.repository` | `GenerationRepository`, `ArtifactRepository`, `ClarificationSessionRepository`, `JournalRepository` | Доступ к таблицам схемы `qa` |
| `application.service` | `GenerationFlowService`, `SyncGenerationService`, `OpenRouterClient`, `PdfToMdClient`, `TestCaseMarkdownRenderer`, `AnswerMerger`, `JournalService`, `ExpirationCleaner` | Оркестрация сессий, LLM, скачивание, рендер, дедупликация ответов, журнал, очистка по сроку |

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

Исполняемый JAR: `target/md-to-tests-2.3.0.jar` (`artifactId` и `version` из `pom.xml`).

Запуск с горячей перезагрузкой не настроен; перезапускайте процесс после изменений.

Тесты (модульные, без БД и сети):

- `SyncGenerationServiceTest` — разбор ответа модели, валидация, санитизация по документу, уточнения без типа (мок `OpenRouterClient`).
- `AnswerMergerTest` — дедупликация ответов по паре `topicKey` + `question` (побеждает последний, пустые пропускаются) и проверка `expectedVersion`.
- `JournalServiceValidationTest` — правила валидации записей журнала: тип, заголовок, `createdAt`, `taskId`, `payload`.

Интеграционных тестов с PostgreSQL в модуле нет.

Lombok подключён как `optional` и требует поддержки обработки аннотаций в IDE.

Добавление эндпоинта:

1. Записи запроса и ответа добавьте в `application.dto.Dtos`.
2. Логику — в соответствующий сервис из `application.service`.
3. Эндпоинт — в контроллер из `application.controller`.
4. Новые ошибки — доменные исключения в `application.exception` и обработчик в `GlobalExceptionHandler`.

Docker: сборка в два этапа (`maven:3.9-eclipse-temurin-21` и `eclipse-temurin:21-jre-jammy`), запуск от пользователя `app`. Каталог `/app/data` в образе сервис не использует: файлы хранятся в БД. Подробности — в [DOCKER.md](../DOCKER.md).

## Ограничения

- **Сессии в БД.** Сессии уточнений и история ответов переживают перезапуск сервиса. Срок хранения — 30 дней; ограничения по количеству сессий нет.
- **Блокировка на время вызова модели.** `POST /api/generate/{taskId}/answers` выполняет вызов OpenRouter внутри транзакции с блокировкой строки сессии (`SELECT ... FOR UPDATE`). Параллельные ответы на тот же `taskId` ждут завершения текущего вызова; ожидание может длиться до `openrouter.timeout-seconds` (по умолчанию 600 с). Запрос с устаревшим `expectedVersion` после ожидания получит `409`; запрос без `expectedVersion` применится поверх результата первого.
- **Откат.** При любом сбое ответа на уточнения (включая `502` от модели и ошибку БД) транзакция откатывается: файлы, история ответов, `version` и `round` остаются прежними. При первичной генерации вызов модели идёт до транзакции; при ошибке БД файлы не сохраняются, сессия не создаётся, клиент получает `500` и не получает `taskId`.
- **Ссылки меняются при каждом ответе.** Файлы прогона получают новые UUID после каждого ответа на уточнения. Ссылки из предыдущего ответа перестают работать (`400 Файл не найден`).
- **Размер документа.** Лимит `max-markdown-chars` проверяется только при первичной генерации, которая создаёт сессию (статус `NEEDS_CLARIFICATION`). Ответы на уточнения его не проверяют.
- **Модель.** Нужна модель, поддерживающая `response_format: json_object`. Если OpenRouter или модель его не поддерживает, ответ приходит не в JSON и сервис возвращает `502`. Значение по умолчанию класса `openrouter/free` отличается от значения в `application.yml`; действует значение из yml.
- **Редиректы pdf-to-md.** Проверяются схема и хост каждого перехода (см. [Безопасность](#безопасность)), но не IP-адрес: защиты от DNS-rebinding нет, порт не ограничивается.
- **Имена файлов.** Для русских заголовков slug не содержит кириллицы, поэтому файлы получают имена вида `TC-UI-001_test_case.md`.
- **Неиспользуемый код.**
  - `TestCaseMarkdownRenderer.renderIndex` (помечен `@deprecated`) — не вызывается;
  - конструктор `Clarification(requirementRef, question, reason)` внутри модуля не используется (вызывается `Clarification.missing(...)` и канонический конструктор);
  - `StorageException` не выбрасывается (файлов на диске больше нет), но класс и обработчик в `GlobalExceptionHandler` остались.
  
  Перед удалением проверьте внешних потребителей.
- **Параметр `isStep`/`isNegative`.** В `SyncGenerationService.sanitizeText` эти параметры не влияют на результат; различие шаг/ожидаемый результат для NEGATIVE обеспечивается вызывающим кодом.
