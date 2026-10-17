# md-to-tests

Сервис генерации тест-кейсов в Markdown из документов (ТЗ, Swagger/OpenAPI).
Работает в паре с [`pdf-to-md`](../pdf-to-md): принимает либо готовый Markdown,
либо URL на `pdf-to-md`.

- **Вход:** Markdown-документ (`markdown`) или ссылка на готовый MD в `pdf-to-md` (`pdfToMdUrl`).
- **Выход:** один MD-файл на каждый тест-кейс (`<ID>_<slug>.md`), плюс `_index.md` и `_clarifications.md`.
- **Уточнения:** тест-кейсы генерируются и сохраняются всегда. Если модели не хватает
  данных, в MD появляется блок `> ⚠️ **Требует уточнения:** ...`, а ответить на вопросы
  можно через REST-цикл `POST /api/generate/{taskId}/answers` — MD-файлы перезапишутся
  улучшенными версиями.

---

## Содержание

- [Возможности](#возможности)
- [Архитектура](#архитектура)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
- [Конфигурация](#конфигурация)
- [API](#api)
- [Интеграция с pdf-to-md](#интеграция-с-pdf-to-md)
- [Формат результата](#формат-результата)
- [Разработка](#разработка)
- [Ограничения](#ограничения)

---

## Возможности

- `POST /api/generate` — генерация тест-кейсов из `markdown` или `pdfToMdUrl`.
- `POST /api/generate/{taskId}/answers` — ответы на уточнения; MD-файлы перезаписываются.
- **Один тест-кейс = один MD-файл**, имя `<ID>_<slug>.md` (например `TC-UI-001_login.md`).
- `_index.md` — оглавление со ссылками на файлы тест-кейсов.
- `_clarifications.md` — сводка замечаний (если есть).
- `GET /api/files/{filename}` — скачивание MD (`text/markdown; charset=UTF-8`).
- `GET /actuator/health` — healthcheck.
- Защита от path traversal, TTL сессий, валидация конфигурации на старте.

---

## Архитектура

```
┌──────────────────┐      markdown/pdfToMdUrl      ┌───────────────┐
│  Client          │ ────────────────────────────► │ md-to-tests   │
│  (Postman/curl)  │ ◄──────────────────────────── │  :8082        │
└──────────────────┘      files[] + status         └───────┬───────┘
                                                          │
                                  ┌───────────────────────┼─────────────────────┐
                                  ▼                       ▼                     ▼
                          OpenRouter (LLM)        pdf-to-md :8080        data/tests/*.md
                          (JSON transport)        (опционально)          (хранилище)
```

- `OpenRouterClient` — единственная точка общения с LLM. Возвращает строку JSON.
- `SyncGenerationService` — промпты + парсинг JSON в `TestCase`/`Clarification`.
- `TestCaseMarkdownRenderer` — рендер MD (с блоками уточнений).
- `TestStorageService` — запись/чтение MD с защитой от path traversal.
- `SessionStore` — in-memory сессии уточнений с TTL.
- `PdfToMdClient` — скачивание MD из `pdf-to-md`.

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
| `app.storage.directory` | `./data/tests` | Куда сохранять MD |
| `app.storage.base-url` | `http://localhost:8082` | База для `downloadUrl` |
| `app.pdf-to-md.base-url` | `http://localhost:8080` | Адрес `pdf-to-md` |
| `app.pdf-to-md.connect-timeout-seconds` | `10` | Таймаут соединения с `pdf-to-md` |
| `app.pdf-to-md.read-timeout-seconds` | `120` | Таймаут чтения из `pdf-to-md` |
| `app.sessions.ttl-minutes` | `120` | TTL сессии уточнений |
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
или
```json
{ "pdfToMdUrl": "http://localhost:8080/api/files/abc123_document.md" }
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
      "steps": ["Открыть страницу", "Ввести email и пароль", "Нажать Войти"],
      "expectedResult": "Пользователь авторизован"
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
| `400` | Не передан `markdown`/`pdfToMdUrl`, пустой `markdown`, некорректный URL |
| `502` | Ошибка OpenRouter или `pdf-to-md` |
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
| `502` | Ошибка OpenRouter |
| `500` | Ошибка хранилища |

---

### `GET /api/files/{filename}`

Скачивание MD-файла. `Content-Type: text/markdown; charset=UTF-8`.
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

1. **`pdf-to-md`:** загрузите PDF:
   ```bash
   curl -F "file=@document.pdf" http://localhost:8080/api/convert
   ```
   В ответе — `downloadUrl`, например:
   `http://localhost:8080/api/files/abc123_document.md`.

2. **`md-to-tests`:** передайте `downloadUrl`:
   ```bash
   curl -X POST http://localhost:8082/api/generate \
     -H "Content-Type: application/json" \
     -d '{"pdfToMdUrl":"http://localhost:8080/api/files/abc123_document.md"}'
   ```

3. Скачайте MD-файлы из `files[].downloadUrl`.

Если `status=NEEDS_CLARIFICATION` — ответьте через `/answers` и получите обновлённые файлы.

---

## Формат результата

```
data/tests/
├── TC-UI-001_login.md
├── TC-API-002_create_user.md
├── _clarifications.md
└── _index.md
```

Пример `TC-UI-001_login.md`:

```markdown
# TC-UI-001 — Логин пользователя

- **ID:** TC-UI-001
- **Тип:** UI
- **Требование:** REQ-001

## ⚠️ Требует уточнения

> ⚠️ **Требует уточнения:** необходим пароль
>
> _Причина:_ В ТЗ не указаны требования к паролю
>
> _Ссылка на требование:_ REQ-001

## Шаги

1. Открыть страницу входа
2. Ввести email и пароль
3. Нажать «Войти»

## Ожидаемый результат

Пользователь авторизован, отображается главная страница.
```

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
| `application.service` | Бизнес-логика, LLM-клиент, рендер MD, хранилище |

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
- **`openrouter/free`** — модель может быть недоступна; при ошибке `502` проверьте
  `openrouter.model` и лимиты аккаунта.
- **`response_format: json_object`** обязателен для корректного парсинга. Если модель
  его не поддерживает, `SyncGenerationService.parseResponse` выбросит `GenerationException`.