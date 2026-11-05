package application.controller;

import application.dto.Dtos.AnswerRequest;
import application.dto.Dtos.DraftRequest;
import application.dto.Dtos.GenerateRequest;
import application.dto.Dtos.GenerateResponse;
import application.service.GenerationFlowService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
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
 * Эндпоинты: {@code POST} — первичная генерация; {@code GET /{taskId}} — состояние сессии;
 * {@code POST /{taskId}/answers} — ответы на уточнения; {@code PUT /{taskId}/draft} — черновик.
 */
@RestController
@RequestMapping("/api/generate")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationFlowService flow;

    /**
     * Первичная генерация тест-кейсов.
     *
     * @param request тело запроса; должен быть задан {@code markdown} или {@code pdfToMdUrl}
     * @return {@code 200 OK} с {@link GenerateResponse}, включая {@code version}
     */
    @PostMapping
    public ResponseEntity<GenerateResponse> generate(@RequestBody GenerateRequest request) {
        return ResponseEntity.ok(flow.generate(request));
    }

    /**
     * Состояние сессии уточнений в формате последнего ответа плюс {@code version}, {@code round},
     * {@code answers}, {@code draft}, {@code expiresAt}.
     *
     * @param taskId идентификатор прогона
     * @return {@code 200 OK} с JSON-объектом состояния
     * @throws IllegalArgumentException сессия не найдена или истекла (ответ {@code 400})
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<JsonNode> get(@PathVariable String taskId) {
        return ResponseEntity.ok(flow.get(taskId));
    }

    /**
     * Ответы пользователя на уточнения.
     *
     * @param taskId  идентификатор прогона
     * @param request ответы и необязательный {@code expectedVersion}
     * @return {@code 200 OK} с {@link GenerateResponse} и увеличенной {@code version}
     * @throws IllegalArgumentException сессия не найдена или истекла (ответ {@code 400})
     * @throws application.exception.ConflictException версия не совпадает (ответ {@code 409})
     */
    @PostMapping("/{taskId}/answers")
    public ResponseEntity<GenerateResponse> answer(@PathVariable String taskId,
                                                   @RequestBody AnswerRequest request) {
        return ResponseEntity.ok(flow.answer(taskId, request));
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

