package application.service;

import application.config.AppProperties;
import application.exception.StorageException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestStorageService {

    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_\\-]+\\.md$");

    private final AppProperties props;

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(props.getStorage().getDirectory());
            log.info("Директория для тест-кейсов: {}", props.getStorage().getDirectory().toAbsolutePath());
        } catch (IOException e) {
            throw new StorageException("Не удалось создать директорию", e);
        }
    }

    public SavedFile save(String filename, String content) {
        if (!SAFE_NAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Некорректное имя файла: " + filename);
        }
        Path target = props.getStorage().getDirectory().resolve(filename);
        try {
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new StorageException("Не удалось сохранить " + target, e);
        }
        log.info("Сохранён тест-кейс: {} ({} символов)", target.getFileName(), content.length());
        String downloadUrl = props.getStorage().getBaseUrl() + "/api/files/" + filename;
        return new SavedFile(filename, target, downloadUrl);
    }

    public Resource load(String filename) {
        if (filename == null || !SAFE_NAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Некорректное имя файла");
        }
        Path target = props.getStorage().getDirectory().resolve(filename).normalize();
        if (!target.startsWith(props.getStorage().getDirectory())) {
            throw new IllegalArgumentException("Некорректный путь");
        }
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Файл не найден: " + filename);
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            throw new StorageException("Некорректный путь к файлу", e);
        }
    }

    public record SavedFile(String filename, Path path, String downloadUrl) {
    }
}
