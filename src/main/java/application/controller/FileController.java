package application.controller;

import application.service.TestStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * REST-контроллер скачивания сгенерированных Markdown-файлов по адресу {@code /api/files/{filename}}.
 * <p>
 * Файлы читаются через {@link TestStorageService#load(String)}, который проверяет имя файла
 * и защищает от path traversal.
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final TestStorageService storage;

    /**
     * Отдаёт файл как вложение с {@code Content-Type: text/markdown; charset=UTF-8}.
     * Заголовок {@code Content-Disposition: attachment} задаёт имя файла, {@code Content-Length}
     * выставляется, если размер удалось определить.
     *
     * @param filename имя файла вида {@code [a-zA-Z0-9_-]+.md}
     * @return {@code 200 OK} с телом файла
     * @throws IllegalArgumentException если имя некорректно, выходит за пределы каталога
     *                                  или файл не найден (ответ {@code 400}, см.
     *                                  {@link application.exception.GlobalExceptionHandler})
     */
    @GetMapping("/{filename}")
    public ResponseEntity<Resource> download(@PathVariable String filename) {
        Resource resource = storage.load(filename);
        long length;
        try {
            length = resource.contentLength();
        } catch (IOException e) {
            length = -1;
        }
        var builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resource.getFilename() + "\"");
        if (length >= 0) {
            builder.contentLength(length);
        }
        return builder.body(resource);
    }
}
