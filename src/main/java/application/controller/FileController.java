package application.controller;

import application.repository.ArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST-контроллер скачивания артефактов по UUID: {@code GET /api/files/{id}}.
 * <p>
 * Тело — содержимое из {@code qa.artifacts}, {@code Content-Type} — из {@code content_type},
 * {@code Content-Disposition: attachment; filename="..."}. Некорректный или неизвестный UUID → 400.
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    /** Текст ошибки для неизвестного или некорректного id. */
    static final String NOT_FOUND = "Файл не найден";

    private final ArtifactRepository artifacts;

    /**
     * Отдаёт артефакт как вложение.
     *
     * @param id UUID артефакта
     * @return {@code 200 OK} с телом файла
     * @throws IllegalArgumentException если id не UUID, файл не найден или срок хранения истёк
     *                                  (ответ {@code 400}, см. {@link application.exception.GlobalExceptionHandler})
     */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        UUID uuid = parseId(id);
        var file = artifacts.findById(uuid).orElseThrow(() -> new IllegalArgumentException(NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }

    /**
     * Разбирает UUID из пути; при ошибке возвращает ту же ошибку, что и для отсутствующего файла.
     *
     * @param id строка из пути
     * @return UUID
     * @throws IllegalArgumentException если строка не UUID
     */
    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(NOT_FOUND);
        }
    }
}
