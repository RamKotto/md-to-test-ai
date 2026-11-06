package application.controller;

import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.DraftRequest;
import application.dto.Dtos.GenerateRequest;
import application.service.GenerationFlowService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-контроллер генерации тест-кейсов ({@code /api/generate}). Логика — в {@link GenerationFlowService}.
 * <p>
 * Эндпоинты: {@code POST} — первичная генерация, {@code 202}; {@code GET /{taskId}} — состояние задачи и сессии;
 * {@code POST /{taskId}/answers} — ответы на уточнения, {@code 202}; {@code PUT /{taskId}/draft} — черновик.
 * Долгие операции выполняются в фоне: фронтенд опрашивает {@code GET}, пока {@code jobStatus = PROCESSING}.
 */
@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationFlowService flow;

    /**
     * Первичная генерация тест-кейсов в фоне. Синхронно проверяется вход; ответ создаётся сразу.
     *
     * @param request тело запроса; должен быть задан {@code markdown} или {@code pdfToMdUrl}
     * @return {@code 202 Accepted} с состоянием задачи: {@code taskId}, {@code jobStatus = PROCESSING},
     *         {@code status = null}, {@code version = 0}, пустые списки
     * @throws IllegalArgumentException вход некорректен (ответ {@code 400})
     */
    @PostMapping
    public ResponseEntity<JsonNode> generate(@RequestBody GenerateRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(flow.generate(request));
    }

    /**
     * Состояние задачи и сессии уточнений: поля задачи ({@code jobStatus}, {@code jobError}, {@code jobStartedAt})
     * и данные последней успешной версии ({@code version}, {@code round}, {@code answers}, {@code draft},
     * {@code expiresAt}).
     *
     * @param taskId идентификатор прогона
     * @return {@code 200 OK} с JSON-объектом состояния
     * @throws IllegalArgumentException нет ни задачи, ни сессии (ответ {@code 400})
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<JsonNode> get(@PathVariable String taskId) {
        return ResponseEntity.ok(flow.get(taskId));
    }

    /**
     * Ответы пользователя на уточнения в фоне.
     *
     * @param taskId  идентификатор прогона
     * @param request ответы и необязательный {@code expectedVersion}
     * @return {@code 202 Accepted} с состоянием задачи ({@code jobStatus = PROCESSING}) и данными последней версии
     * @throws IllegalArgumentException сессия не найдена, истекла или уже завершена (ответ {@code 400})
     * @throws application.exception.ConflictException версия не совпадает или операция уже выполняется (ответ {@code 409})
     */
    @PostMapping("/{taskId}/answers")
    public ResponseEntity<JsonNode> answer(@PathVariable String taskId,
                                           @RequestBody AnswerRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(flow.answer(taskId, request));
    }

    /**
     * Перезаписывает черновик ответов целиком; версия не меняется.
     *
     * @param taskId  идентификатор прогона
     * @param request черновики
     * @return {@code 204 No Content}
     * @throws IllegalArgumentException сессия не найдена или истекла (ответ {@code 400})
     */
    @PutMapping("/{taskId}/draft")
    public ResponseEntity<Void> saveDraft(@PathVariable String taskId, @RequestBody DraftRequest request) {
        flow.saveDraft(taskId, request.draft());
        return ResponseEntity.noContent().build();
    }
}
