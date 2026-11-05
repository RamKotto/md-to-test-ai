package application.controller;

import application.dto.Dtos.JournalEntry;
import application.dto.Dtos.JournalEntryRequest;
import application.service.JournalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-контроллер журнала ({@code /api/journal}). Логика и валидация — в {@link JournalService}.
 */
@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
public class JournalController {

    private final JournalService journal;

    /**
     * Записи журнала с неистёкшим сроком, новые первыми, не более 500.
     *
     * @return {@code 200 OK} со списком записей
     */
    @GetMapping
    public List<JournalEntry> list() {
        return journal.list();
    }

    /**
     * Создаёт или обновляет запись журнала.
     *
     * @param id      идентификатор записи (UUID)
     * @param request тело записи
     * @return {@code 200 OK} с сохранённой записью
     * @throws IllegalArgumentException тело или id некорректны (ответ {@code 400})
     */
    @PutMapping("/{id}")
    public JournalEntry upsert(@PathVariable String id, @RequestBody JournalEntryRequest request) {
        return journal.upsert(id, request);
    }

    /**
     * Удаляет запись журнала.
     *
     * @param id идентификатор записи
     * @return {@code 204 No Content}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        journal.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Очищает весь журнал.
     *
     * @return {@code 204 No Content}
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteAll() {
        journal.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
