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

/**
 * Файловое хранилище Markdown-результатов в каталоге {@code app.storage.directory}.
 * <p>
 * Принимает только имена вида {@code [a-zA-Z0-9_-]+.md}, что исключает path traversal. Записи
 * атомарны: при ошибке каталог возвращается в исходное состояние. Существующие файлы
 * сохраняются, только если это явно указано в операции (см. {@link #replaceAll}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestStorageService {

    /** Допустимое имя файла: латиница, цифры, {@code _} и {@code -}, расширение {@code .md}. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9_\\-]+\\.md$");

    /** Подпапка внутри staging для резервных копий (удаляется вместе со staging). */
    private static final String BACKUP_DIR = ".backup";

    private final AppProperties props;

    /**
     * Создаёт каталог хранения при старте приложения, включая родительские каталоги.
     * Вызывается контейнером Spring после внедрения зависимостей.
     *
     * @throws StorageException если каталог не удалось создать; приложение не стартует
     */
    @PostConstruct
    void init() {
        try {
            Files.createDirectories(props.getStorage().getDirectory());
            log.info("Директория для тест-кейсов: {}", props.getStorage().getDirectory().toAbsolutePath());
        } catch (IOException e) {
            throw new StorageException("Не удалось создать директорию", e);
        }
    }

    /**
     * Сохраняет один файл. Если имя занято, файл получает суффикс {@code _2}, {@code _3} и т. д.
     * Реализация делегирует в {@link #saveAll}, поэтому возвращённое имя может отличаться от запрошенного.
     *
     * @param filename имя файла вида {@code [a-zA-Z0-9_-]+.md}
     * @param content  содержимое в UTF-8; {@code null} записывается как пустая строка
     * @return сохранённый файл с итоговым именем и URL для скачивания
     * @throws IllegalArgumentException если имя некорректно
     * @throws StorageException         если запись не удалась
     */
    public SavedFile save(String filename, String content) {
        var saved = saveAll(List.of(new PendingFile(filename, content)));
        return saved.get(0);
    }

    /**
     * Атомарно сохраняет набор файлов с уникализацией имён (суффикс {@code _2}, {@code _3} и т. д.).
     * Существующие файлы не перезаписываются. Используется при первичной генерации.
     * Либо все файлы попадают в целевой каталог, либо состояние откатывается.
     *
     * @param files файлы для записи; {@code null} или пустой список дают пустой результат
     * @return сохранённые файлы в порядке входного списка
     * @throws IllegalArgumentException если какое-либо имя некорректно или элемент равен {@code null}
     * @throws StorageException         если запись или откат не удались
     */
    public List<SavedFile> saveAll(List<PendingFile> files) {
        if (files == null || files.isEmpty()) return List.of();
        validateNames(files);
        return commit(files, List.of(), true);
    }

    /**
     * Атомарно заменяет файлы одного прогона (используется при ответах на уточнения).
     * <p>
     * Файлы из {@code files} пишутся под своими именами: существующие файлы с теми же именами
     * заменяются. Файлы из {@code toDelete} удаляются, но только если их нет среди имён {@code files}.
     * Либо всё применяется, либо состояние откатывается к исходному (старые файлы восстанавливаются
     * из резервной копии).
     *
     * @param files    файлы для записи; {@code null} трактуется как пустой список
     * @param toDelete имена файлов для удаления; {@code null} трактуется как пустой набор
     * @return сохранённые файлы в порядке входного списка
     * @throws IllegalArgumentException если имя некорректно, либо в {@code files} есть дубликаты
     * @throws StorageException         если операция или откат не удались
     */
    public List<SavedFile> replaceAll(List<PendingFile> files, Collection<String> toDelete) {
        List<PendingFile> safeFiles = files == null ? List.of() : files;
        List<String> safeDelete = new ArrayList<>();
        if (toDelete != null) {
            for (String name : toDelete) {
                validateName(name);
                safeDelete.add(name);
            }
        }
        validateNames(safeFiles);
        if (safeFiles.isEmpty() && safeDelete.isEmpty()) return List.of();
        return commit(safeFiles, safeDelete, false);
    }

    /**
     * Проверяет каждый элемент набора: не {@code null} и с допустимым именем.
     *
     * @param files набор файлов
     * @throws IllegalArgumentException при первом нарушении
     */
    private void validateNames(List<PendingFile> files) {
        for (PendingFile f : files) {
            if (f == null) {
                throw new IllegalArgumentException("Некорректное имя файла: null");
            }
            validateName(f.filename());
        }
    }

    /**
     * Проверяет имя файла по регулярному выражению {@link #SAFE_NAME}.
     *
     * @param name имя файла
     * @throws IllegalArgumentException если имя {@code null} или не соответствует шаблону
     */
    private void validateName(String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Некорректное имя файла: " + name);
        }
    }

    /**
     * Общая логика атомарной записи с откатом. Порядок шагов:
     * <ol>
     *   <li>Имена: при {@code uniquify = true} каждое имя уникализируется относительно диска и
     *       батча; при {@code false} дубликаты в батче запрещены. Всё это выполняется до записи.</li>
     *   <li>Новые файлы пишутся в staging-каталог {@code .staging-*}, созданный внутри целевого каталога,
     *       чтобы оставаться на той же файловой системе.</li>
     *   <li>Существующие файлы с теми же именами переносятся в {@code .backup} внутри staging.</li>
     *   <li>Новые файлы переносятся в целевой каталог: сначала {@code ATOMIC_MOVE}, при
     *       {@link AtomicMoveNotSupportedException} — обычный перенос с заменой.</li>
     *   <li>Файлы из {@code toDelete}, которых нет среди новых имён, переносятся в {@code .backup}.
     *       Это удаление тоже откатываемое.</li>
     * </ol>
     * При {@link IOException} или {@link RuntimeException} на любом шаге уже установленные новые файлы
     * удаляются, резервные копии возвращаются на место, после чего исключение пробрасывается.
     * Staging удаляется в {@code finally}. Если откат не выполнен полностью, staging сохраняется
     * для ручного восстановления, и об этом пишется ошибка в лог.
     *
     * @param files     файлы для записи
     * @param toDelete  имена для удаления
     * @param uniquify  {@code true} — подбирать свободные имена; {@code false} — использовать имена как есть
     * @return сохранённые файлы в порядке входного списка
     * @throws IllegalArgumentException если {@code uniquify = false} и имена в батче повторяются
     * @throws StorageException         при ошибке записи или переноса
     */
    private List<SavedFile> commit(List<PendingFile> files, List<String> toDelete, boolean uniquify) {
        Path targetDir = props.getStorage().getDirectory();

        List<String> names = new ArrayList<>(files.size());
        Set<String> usedNames = new HashSet<>();
        for (PendingFile f : files) {
            String name;
            if (uniquify) {
                name = resolveUniqueName(f.filename(), usedNames);
            } else {
                name = f.filename();
                if (!usedNames.add(name)) {
                    throw new IllegalArgumentException("Дубликат имени файла в наборе: " + name);
                }
            }
            names.add(name);
        }
        Set<String> newNames = new HashSet<>(names);
        List<String> deleteNames = toDelete.stream()
                .filter(n -> !newNames.contains(n))
                .distinct()
                .toList();

        Path stagingDir;
        try {
            stagingDir = Files.createTempDirectory(targetDir, ".staging-");
        } catch (IOException e) {
            throw new StorageException("Не удалось создать staging-директорию в " + targetDir, e);
        }
        Path backupDir = stagingDir.resolve(BACKUP_DIR);
        List<Path> installed = new ArrayList<>();
        List<Map.Entry<Path, Path>> movedAside = new ArrayList<>();
        boolean keepStaging = false;

        try {
            List<Path> staged = new ArrayList<>(files.size());
            for (int i = 0; i < files.size(); i++) {
                String content = files.get(i).content() == null ? "" : files.get(i).content();
                Path stagedPath = stagingDir.resolve(names.get(i));
                Files.writeString(stagedPath, content);
                staged.add(stagedPath);
            }

            Files.createDirectories(backupDir);

            for (String name : names) {
                moveAside(targetDir.resolve(name), backupDir, movedAside);
            }

            for (int i = 0; i < names.size(); i++) {
                Path target = targetDir.resolve(names.get(i));
                moveAtomicallyOrFallback(staged.get(i), target);
                installed.add(target);
            }

            for (String name : deleteNames) {
                moveAside(targetDir.resolve(name), backupDir, movedAside);
            }

            List<SavedFile> result = new ArrayList<>(names.size());
            for (String name : names) {
                Path target = targetDir.resolve(name);
                String downloadUrl = props.getStorage().getBaseUrl() + "/api/files/" + name;
                result.add(new SavedFile(name, target, downloadUrl));
                log.info("Сохранён тест-кейс: {}", name);
            }
            return result;
        } catch (IOException e) {
            keepStaging = !rollback(installed, movedAside);
            throw new StorageException("Не удалось сохранить тест-кейсы", e);
        } catch (RuntimeException e) {
            keepStaging = !rollback(installed, movedAside);
            throw e;
        } finally {
            if (keepStaging) {
                log.error("Откат не завершён полностью; резервные копии сохранены в {}", stagingDir);
            } else {
                deleteRecursively(stagingDir);
            }
        }
    }

    /**
     * Переносит существующий файл в каталог резервных копий и запоминает перенос для отката.
     * Если файла нет, ничего не делает.
     *
     * @param target     путь к файлу в целевом каталоге
     * @param backupDir  каталог резервных копий
     * @param movedAside журнал переносов, в который добавляется пара (исходный путь, путь в резерве)
     * @throws IOException если перенос не удался
     */
    private void moveAside(Path target, Path backupDir, List<Map.Entry<Path, Path>> movedAside)
            throws IOException {
        if (!Files.exists(target)) return;
        Path backup = backupDir.resolve(target.getFileName().toString());
        Files.move(target, backup);
        movedAside.add(Map.entry(target, backup));
    }

    /**
     * Откат: удаляет установленные новые файлы в обратном порядке и возвращает резервные копии
     * на исходные места. Ошибки логируются, отдельные шаги отката продолжаются.
     *
     * @param installed  пути новых файлов, которые уже перенесены в целевой каталог
     * @param movedAside журнал переносов в резерв
     * @return {@code true}, если откат выполнен полностью; {@code false} — были ошибки
     */
    private boolean rollback(List<Path> installed, List<Map.Entry<Path, Path>> movedAside) {
        boolean ok = true;
        log.warn("Откат записи тест-кейсов: новых файлов {}, резервных копий {}",
                installed.size(), movedAside.size());
        for (int i = installed.size() - 1; i >= 0; i--) {
            Path p = installed.get(i);
            try {
                Files.deleteIfExists(p);
            } catch (IOException ex) {
                ok = false;
                log.error("Откат: не удалось удалить {}", p, ex);
            }
        }
        for (int i = movedAside.size() - 1; i >= 0; i--) {
            Map.Entry<Path, Path> entry = movedAside.get(i);
            try {
                Files.move(entry.getValue(), entry.getKey(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                ok = false;
                log.error("Откат: не удалось восстановить {} из {}", entry.getKey(), entry.getValue(), ex);
            }
        }
        return ok;
    }

    /**
     * Переносит файл с атомарной заменой. Если файловая система не поддерживает атомарный перенос
     * (разные диски Windows, сетевые шары, FAT32), выполняется обычный перенос с заменой.
     *
     * @param source исходный файл в staging
     * @param target целевой путь
     * @throws IOException если перенос не удался
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

    /**
     * Проверяет, занято ли имя файла в хранилище. Нужно планировщику имён при ответах на уточнения,
     * чтобы новое имя не перезаписало файл другого прогона.
     * Некорректное имя считается свободным: такой файл не может существовать в хранилище.
     *
     * @param filename имя файла
     * @return {@code true}, если имя корректно и файл с таким именем существует
     */
    public boolean exists(String filename) {
        if (filename == null || !SAFE_NAME.matcher(filename).matches()) {
            return false;
        }
        return Files.exists(props.getStorage().getDirectory().resolve(filename));
    }

    /**
     * Возвращает ресурс для чтения файла. Имя проверяется по {@link #SAFE_NAME}, путь после
     * нормализации должен оставаться внутри каталога хранения.
     *
     * @param filename имя файла вида {@code [a-zA-Z0-9_-]+.md}
     * @return {@link UrlResource} на файл
     * @throws IllegalArgumentException если имя некорректно, путь выходит за пределы каталога
     *                                  или файл не существует (ответ {@code 400})
     * @throws StorageException         если путь не удалось преобразовать в URL (ответ {@code 500})
     */
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

    /**
     * Подбирает свободное имя на основе {@code desired}: имя не должно быть в {@code used} и не должно
     * существовать на диске. Перебираются варианты {@code <base>_2.md}, {@code <base>_3.md} и т. д.
     * Найденное имя добавляется в {@code used}.
     *
     * @param desired желаемое имя, уже проверенное по {@link #SAFE_NAME}
     * @param used    имена, уже назначенные в текущем батче; изменяется методом
     * @return свободное имя
     */
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

    /**
     * Рекурсивно удаляет каталог (staging или его часть). Ошибки игнорируются: удаление
     * выполняется по принципу best effort, а откат уже завершён.
     *
     * @param dir каталог для удаления
     */
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

    /**
     * Результат сохранения одного файла.
     *
     * @param filename    итоговое имя файла в хранилище (может отличаться от запрошенного из-за уникализации)
     * @param path        абсолютный или относительный путь к файлу в каталоге хранения
     * @param downloadUrl URL для скачивания вида {@code {app.storage.base-url}/api/files/{filename}}
     */
    public record SavedFile(String filename, Path path, String downloadUrl) {
    }

    /**
     * Файл, который нужно записать.
     *
     * @param filename имя файла вида {@code [a-zA-Z0-9_-]+.md}
     * @param content  содержимое в UTF-8; {@code null} записывается как пустая строка
     */
    public record PendingFile(String filename, String content) {
    }
}
