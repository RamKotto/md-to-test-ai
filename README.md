# md-to-tests

Сервис генерации тест-кейсов в Markdown из готового Markdown-документа.
Работает в паре с [`pdf-to-md`](../pdf-to-md): принимает либо raw Markdown текстом,
либо ссылку на готовый `.md`, которую вернул `pdf-to-md`.

- **Вход:** `markdown` (raw-текст) **или** `pdfToMdUrl` (абсолютный URL на `.md` из `pdf-to-md`).
- **Выход:** один `.md` на каждый тест-кейс (`<ID>_<slug>.md`), плюс `_index.md` и `_clarifications.md`.
- **Строгий формат:** тест-кейсы всегда соответствуют одной JSON-схеме — независимо от модели.
- **Уточнения:** тест-кейсы создаются всегда. Если модели не хватает данных — в MD появляется блок
  `> ⚠️ Требует уточнения: ...`, а ответить можно через REST-цикл `POST /api/generate/{taskId}/answers`.

---

## Содержание

- [Возможности](#возможности)
- [Архитектура](#архитектура)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Конфигурация](#конфигурация)
- [API](#api)
- [Интеграция с pdf-to-md](#интеграция-с-pdf-to-md)
- [Формат тест-кейса](#формат-тест-кейса)
- [Безопасность](#безопасность)
- [Разработка](#разработка)
- [Ограничения](#ограничения)

---

## Возможности

- `POST /api/generate` — генерация тест-кейсов из `markdown` или `pdfToMdUrl`.
- `POST /api/generate/{taskId}/answers` — ответы на уточнения; MD-файлы перезаписываются.
- **Один тест-кейс = один MD-файл**, имя `<prefix?><ID>_<slug>.md` (например `TC-UI-001_login.md`).
- `_index.md` — оглавление со ссылками на файлы тест-кейсов.
- `_clarifications.md` — сводка уточнений (если есть).
- `GET /api/files/{filename}` — скачивание MD (`text/markdown; charset=UTF-8`).
- `GET /actuator/health` — healthcheck.
- **Строгая валидация ответа модели:** `id`, `type`, `priority`, `preconditions`, `steps`,
  `expectedResult`, `requirementRef` — иначе `502`.
- **Дедупликация clarifications** по `requirementRef`.
- **Атомарная запись файлов** через staging + `ATOMIC_MOVE`.
- **Защита от path traversal и SSRF.**
- **TTL и лимиты сессий** уточнений.

---

## Архитектура

```
┌──────────────────┐   markdown / pdfToMdUrl    ┌───────────────┐
│  Client          │ ─────────────────────────► │ md-to-tests   │
│  (Postman/curl)  │ ◄───────────────────────── │  :8082        │
└──────────────────┘   files[] + status         └───────┬───────┘
                                                        │
                                ┌───────────────────────┼─────────────────────┐
                                ▼                       ▼                     ▼
                        OpenRouter (LLM)        pdf-to-md :8080        data/tests/*.md
                        (JSON transport)        (скачивание .md)       (хранилище)
```

- `MarkdownSourceResolver` — единая точка выбора источника: raw `markdown` или `pdfToMdUrl`.
- `PdfToMdClient` — скачивание `.md` по ссылке с SSRF-allowlist'ом.
- `OpenRouterClient` — единственная точка общения с LLM.
- `SyncGenerationService` — промпты, парсинг JSON, строгая валидация, дедуп clarifications.
- `TestCaseMarkdownRenderer` — рендер MD.
- `TestStorageService` — атомарная запись/чтение MD с защитой от path traversal.
- `SessionStore` — in-memory сессии уточнений с TTL и лимитами.

---

## Требования

- Java 21
- Maven 3.8+
- API-ключ OpenRouter
- Запущенный `pdf-to-md` (опционально, только для режима `pdfToMdUrl`)

---

## Быстрый старт

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
mvn clean spring-boot:run
```

Сервис: `http://localhost:8082`.

Проверка:
```bash
curl http://localhost:8082/actuator/health
```
---

## Конфигурация

Все настройки — в `src/main/resources/application.yml`.

| Свойство | По умолчанию | Назначение |
|---|---|---|
| `server.port` | `8082` | Порт приложения |
| `app.storage.directory` | `${user.dir}/data/tests` | Куда сохранять MD |
| `app.storage.base-url` | `http://localhost:8082` | База для `downloadUrl` |
| `app.pdf-to-md.base-url` | `http://localhost:8080` | Адрес `pdf-to-md` |
| `app.pdf-to-md.connect-timeout-seconds` | `10` | Connect-таймаут к `pdf-to-md` |
| `app.pdf-to-md.read-timeout-seconds` | `600` | Read-таймаут к `pdf-to-md` (OCR PDF бывает долгим) |
| `app.pdf-to-md.allowed-hosts` | `[localhost]` | Хосты, с которых разрешено скачивать `.md` |
| `app.sessions.ttl-minutes` | `120` | TTL сессии уточнений |
| `app.sessions.max-count` | `1000` | Максимум сессий (LRU-вытеснение) |
| `app.sessions.max-markdown-chars` | `1000000` | Максимум символов MD в сессии |
| `app.prompts.json-schema` | — | JSON-схема ответа модели |
| `app.prompts.generate` | — | Промпт первичной генерации |
| `app.prompts.answers` | — | Промпт обработки ответов |
| `openrouter.base-url` | `https://openrouter.ai/api/v1` | URL OpenRouter |
| `openrouter.api-key` | `${OPENROUTER_API_KEY:}` | API-ключ (обязателен) |
| `openrouter.model` | `openrouter/free` | Модель |
| `openrouter.max-output-tokens` | `50000` | Лимит токенов ответа |
| `openrouter.timeout-seconds` | `600` | Read-таймаут LLM |
| `openrouter.connect-timeout-seconds` | `30` | Connect-таймаут LLM |

Валидация: приложение не стартует, если `openrouter.api-key` пуст или промпты не заданы.

---

## API

### `POST /api/generate`

**Тело** (одно из двух):

```json
{ "markdown": "# ТЗ\n..." }
```

```json
{ "pdfToMdUrl": "http://localhost:8080/api/files/abc123_document.md" }
```

Опционально — `filename` (используется как префикс для файлов, полезно при интеграции с `pdf-to-md`):

```json
{
  "pdfToMdUrl": "http://localhost:8080/api/files/abc123_document.md",
  "filename": "document.pdf"
}
```

**Ответ `200 OK`:**

```json
{
  "taskId": "d1c8e4a1-...",
  "status": "DONE | NEEDS_CLARIFICATION",
  "testCases": [
    {
      "id": "TC-UI-001",
      "type": "UI",
      "title": "Логин пользователя",
      "requirementRef": "REQ-001",
      "preconditions": ["Пользователь зарегистрирован"],
      "steps": ["Открыть страницу", "Ввести email и пароль", "Нажать Войти"],
      "expectedResult": "Пользователь авторизован",
      "priority": "P1"
    }
  ],
  "clarifications": [
    {
      "requirementRef": "REQ-001",
      "question": "необходим пароль",
      "reason": "В ТЗ не указаны требования к паролю"
    }
  ],
  "files": [
    { "id": "TC-UI-001", "filename": "TC-UI-001_login.md", "downloadUrl": "http://localhost:8082/api/files/TC-UI-001_login.md" },
    { "id": "_clarifications", "filename": "_clarifications.md", "downloadUrl": "http://localhost:8082/api/files/_clarifications.md" },
    { "id": "_index", "filename": "_index.md", "downloadUrl": "http://localhost:8082/api/files/_index.md" }
  ]
}
```

**Важно:** MD-файлы создаются **всегда**, независимо от `status`.
Если `status=NEEDS_CLARIFICATION`, сохраните `taskId` для следующего шага.

**Ошибки:**

| Код | Причина |
|---|---|
| `400` | Не передан `markdown`/`pdfToMdUrl`, пустой `markdown`, неверный URL, хост не в allowlist |
| `502` | Ошибка OpenRouter, ошибка `pdf-to-md`, или ответ модели не соответствует строгой схеме |
| `500` | Ошибка хранилища / непредвиденная ошибка |

---

### `POST /api/generate/{taskId}/answers`

**Тело:**

```json
{
  "answers": [
    { "requirementRef": "REQ-001", "answer": "Пароль: минимум 8 символов, буквы и цифры" }
  ]
}
```

**Ответ `200 OK`** — та же структура `GenerateResponse`:

- `status=DONE` — сессия закрыта, MD-файлы финальные.
- `status=NEEDS_CLARIFICATION` — можно ответить ещё раз с тем же `taskId`.

**Ошибки:**

| Код | Причина |
|---|---|
| `400` | Сессия не найдена/истекла |
| `502` | Ошибка OpenRouter или ответ модели не по схеме |
| `500` | Ошибка хранилища |

---

### `GET /api/files/{filename}`

Скачивание MD-файла. `Content-Type: text/markdown; charset=UTF-8`, `Content-Length` выставляется.
Имя должно соответствовать `^[a-zA-Z0-9_\-]+\.md$` (защита от path traversal).

**Ошибки:**

| Код | Причина |
|---|---|
| `400` | Некорректное имя файла или файл не найден |

---

### `GET /actuator/health`

```json
{ "status": "UP" }
```
---

## Интеграция с pdf-to-md

### Сценарий 1: PDF → `pdf-to-md` → `md-to-tests`

```bash
# Шаг 1: pdf-to-md принимает PDF и сохраняет Markdown
curl -X POST http://localhost:8080/api/convert \
  -F "file=@document.pdf"
# → {
#     "id": "uuid",
#     "filename": "uuid_document.md",
#     "downloadUrl": "http://localhost:8080/api/files/uuid_document.md",
#     "markdown": "# ..."
#   }

# Шаг 2: md-to-tests скачивает .md и генерирует тест-кейсы
curl -X POST http://localhost:8082/api/generate \
  -H "Content-Type: application/json" \
  -d '{
    "pdfToMdUrl": "http://localhost:8080/api/files/uuid_document.md",
    "filename": "document.pdf"
  }'
```

Файлы получат префикс: `document_TC-UI-001_login.md`, `document__index.md`, ...

### Сценарий 2: PDF по ссылке → `pdf-to-md` → `md-to-tests`

```bash
curl -X POST http://localhost:8080/api/convert \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/spec.pdf","filename":"spec.pdf"}'

curl -X POST http://localhost:8082/api/generate \
  -H "Content-Type: application/json" \
  -d '{"pdfToMdUrl":"http://localhost:8080/api/files/uuid_spec.md","filename":"spec.pdf"}'
```

### Сценарий 3: raw Markdown напрямую (без `pdf-to-md`)

```bash
curl -X POST http://localhost:8082/api/generate \
  -H "Content-Type: application/json" \
  -d '{"markdown":"# ТЗ\n\n## REQ-001 Логин\nПользователь входит по email и паролю.\n"}'
```

### Сценарий 4: ссылка на raw MD (минуя `pdf-to-md`)

Добавьте хост в `app.pdf-to-md.allowed-hosts`:

```yaml
app:
  pdf-to-md:
    allowed-hosts:
      - localhost
      - raw.githubusercontent.com
```

```bash
curl -X POST http://localhost:8082/api/generate \
  -H "Content-Type: application/json" \
  -d '{"pdfToMdUrl":"https://raw.githubusercontent.com/example/api/main/README.md"}'
```

---

## Формат тест-кейса

Модель **обязана** вернуть JSON строго по схеме. `SyncGenerationService` валидирует:

| Поле | Правило |
|---|---|
| `id` | `^TC-(UI|API|E2E|NEG|SEC|PERF)-\d{3,}$`, уникален в ответе |
| `type` | `UI`, `API`, `E2E`, `NEGATIVE`, `SECURITY`, `PERFORMANCE` |
| `title` | 3–200 символов, без префикса «Тест-кейс» |
| `requirementRef` | `REQ-XXX` или `N/A` |
| `preconditions` | массив строк (может быть пустым) |
| `steps` | массив строк, **не пустой** |
| `expectedResult` | не пустая строка |
| `priority` | `P0`, `P1`, `P2`, `P3` |
| `clarifications[].requirementRef` | `REQ-XXX` или `N/A` |
| `clarifications[].question` | не пустая строка |

Если модель нарушила схему — `502` с описанием нарушения.
Clarifications дедуплицируются по `requirementRef`: на одно требование — один вопрос.

### Пример результирующего MD

```
data/tests/
├── document_TC-UI-001_login.md
├── document_TC-API-002_create_user.md
├── document__clarifications.md
└── document__index.md
```

`document_TC-UI-001_login.md`:

```markdown
# TC-UI-001 — Логин пользователя

- **ID:** TC-UI-001
- **Тип:** UI
- **Приоритет:** P1
- **Требование:** REQ-001

## ⚠️ Требует уточнения

> ⚠️ **Требует уточнения:** необходим пароль
>
> _Причина:_ В ТЗ не указаны требования к паролю
>
> _Ссылка на требование:_ REQ-001

## Предусловия

- Пользователь зарегистрирован

## Шаги

1. Открыть страницу входа
2. Ввести email и пароль
3. Нажать «Войти»

## Ожидаемый результат

Пользователь авторизован, отображается главная страница
```

---

## Безопасность

- **Path traversal** в `TestStorageService`: `SAFE_NAME` regex `^[a-zA-Z0-9_\-]+\.md$`
    + `normalize()` + `startsWith(directory)`.
- **SSRF** в `PdfToMdClient`: allowlist хостов (`app.pdf-to-md.allowed-hosts`),
  проверка схемы (`http`/`https`), fallback на хост из `base-url`, если список пуст.
- **Атомарная запись:** staging-директория + `Files.move(..., ATOMIC_MOVE)`,
  при ошибке в целевую папку ничего не попадает.
- **Коллизии имён:** автоматический суффикс `_2`, `_3`, ...
- **Лимиты сессий:** TTL, max count, max markdown chars.
- **Валидация ответа LLM:** любое нарушение схемы → `502`, а не «кривой» MD на диске.

---

## Разработка

### Сборка и тесты

```bash
mvn clean verify
```

### Запуск тестов

```bash
mvn test
```

### Структура пакетов

| Пакет | Ответственность |
|---|---|
| `application.config` | `@ConfigurationProperties`, `RestClient` бины |
| `application.controller` | REST-эндпоинты |
| `application.dto` | Records запросов/ответов |
| `application.exception` | Доменные исключения + `@RestControllerAdvice` |
| `application.service` | Бизнес-логика, LLM-клиент, рендер MD, хранилище, сессии |

### Добавление нового эндпоинта

1. DTO — в `application.dto.Dtos`.
2. Логика — в `application.service.*Service`.
3. Контроллер — в `application.controller.*Controller`.
4. Ошибки — доменное исключение в `application.exception` + обработчик в `GlobalExceptionHandler`.

---

## Ограничения

- **Сессии уточнений — in-memory.** При рестарте приложения активные `taskId` теряются.
  Для продакшена — Redis или БД.
- **Один инстанс.** Горизонтальное масштабирование потребует внешнего хранилища сессий
  и общего `app.storage.directory` (например, S3-совместимое).
- **`openrouter/free`** — модель может быть недоступна; при `502` проверьте
  `openrouter.model` и лимиты аккаунта.
- **`response_format: json_object`** обязателен для корректного парсинга. Если модель
  его не поддерживает, `SyncGenerationService.parseResponse` выбросит `GenerationException` → `502`.
- **`pdf-to-md` на другом хосте.** Добавьте его в `app.pdf-to-md.allowed-hosts`,
  иначе `pdfToMdUrl` вернёт `400` «Хост не разрешён».
- **Долгий OCR.** `pdf-to-md` сам ходит в OpenRouter с `timeout-seconds=600`;
  `app.pdf-to-md.read-timeout-seconds` тоже 600 — если OCR занимает больше, увеличьте оба.