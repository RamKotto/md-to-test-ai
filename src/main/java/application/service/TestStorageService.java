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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
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
        var saved = saveAll(List.of(new PendingFile(filename, content)));
        return saved.get(0);
    }

    /**
     * Атомарно сохраняет набор файлов.
     * 1) Валидирует имена и разрешает коллизии (суффикс _2, _3...).
     * 2) Пишет всё во staging-директорию ВНУТРИ целевой папки (та же ФС).
     * 3) Переносит в целевую папку через ATOMIC_MOVE; если ФС не поддерживает
     * атомарный перенос (Windows C:\ -> D:\, сетевые шары, FAT32) — fallback
     * на REPLACE_EXISTING.
     * При любой ошибке на этапе (1)-(2) — ничего не попадает в целевую папку.
     */
    public List<SavedFile> saveAll(List<PendingFile> files) {
        if (files == null || files.isEmpty()) return List.of();

        // (0) Предварительная валидация всех имён ДО любой записи
        for (PendingFile f : files) {
            if (f == null || f.filename() == null || !SAFE_NAME.matcher(f.filename()).matches()) {
                throw new IllegalArgumentException("Некорректное имя файла: "
                        + (f == null ? "null" : f.filename()));
            }
        }

        Path targetDir = props.getStorage().getDirectory();
        Path stagingDir;
        try {
            // staging внутри targetDir — гарантированно на той же ФС, что и target
            stagingDir = Files.createTempDirectory(targetDir, ".staging-");
        } catch (IOException e) {
            throw new StorageException("Не удалось создать staging-директорию в " + targetDir, e);
        }

        List<SavedFile> result = new ArrayList<>(files.size());
        try {
            Set<String> usedNames = new HashSet<>();
            List<Map.Entry<PendingFile, Path>> staged = new ArrayList<>();
            for (PendingFile f : files) {
                String safe = resolveUniqueName(f.filename(), usedNames);
                Path stagedPath = stagingDir.resolve(safe);
                Files.writeString(stagedPath, f.content());
                staged.add(Map.entry(new PendingFile(safe, f.content()), stagedPath));
            }

            for (var entry : staged) {
                String name = entry.getKey().filename();
                Path target = targetDir.resolve(name);
                moveAtomicallyOrFallback(entry.getValue(), target);
                String downloadUrl = props.getStorage().getBaseUrl() + "/api/files/" + name;
                result.add(new SavedFile(name, target, downloadUrl));
                log.info("Сохранён тест-кейс: {} ({} символов)",
                        target.getFileName(), entry.getKey().content().length());
            }
            return result;
        } catch (IOException e) {
            throw new StorageException("Не удалось сохранить тест-кейсы", e);
        } finally {
            deleteRecursively(stagingDir);
        }
    }

    /**
     * Пытается ATOMIC_MOVE. Если ФС не поддерживает атомарный перенос
     * (разные диски Windows, сетевые шары, FAT32) — делает обычный
     * REPLACE_EXISTING, который работает везде.
     */
    private void moveAtomicallyOrFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.debug("ATOMIC_MOVE не поддерживается для {}, fallback на REPLACE_EXISTING", target);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
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

    private String resolveUniqueName(String desired, Set<String> used) {
        String base = desired.endsWith(".md") ? desired.substring(0, desired.length() - 3) : desired;
        String candidate = desired;
        int i = 2;
        while (used.contains(candidate)
                || Files.exists(props.getStorage().getDirectory().resolve(candidate))) {
            candidate = base + "_" + i + ".md";
            i++;
        }
        used.add(candidate);
        return candidate;
    }

    private void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    public record SavedFile(String filename, Path path, String downloadUrl) {
    }

    public record PendingFile(String filename, String content) {
    }
}
