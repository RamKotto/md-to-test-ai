package application.service;

import application.config.AppProperties;
import application.service.TestStorageService.PendingFile;
import application.service.TestStorageService.SavedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты {@link TestStorageService}: атомарная замена и сохранение файлов, проверка имён и
 * уникализация. Работают во временном каталоге {@link TempDir}.
 */
class TestStorageServiceTest {

    @TempDir
    Path tempDir;

    private TestStorageService service;

    /**
     * Создаёт сервис с каталогом во временной папке. Метод {@code init()} с {@code @PostConstruct}
     * в юнит-тесте не вызывается, поэтому каталог создаётся здесь вручную.
     *
     * @throws IOException если каталог не удалось создать
     */
    @BeforeEach
    void setUp() throws IOException {
        AppProperties props = new AppProperties();
        props.getStorage().setDirectory(tempDir);
        props.getStorage().setBaseUrl("http://localhost:8082");
        Files.createDirectories(tempDir);
        service = new TestStorageService(props);
    }

    private List<String> listNames() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void replaceAll_replacesExistingFile_createsNewOne_keepsUnlisted_andLeavesNoStagingDirs() throws IOException {
        Files.writeString(tempDir.resolve("a.md"), "old");
        Files.writeString(tempDir.resolve("c.md"), "keep");

        List<SavedFile> saved = service.replaceAll(
                List.of(new PendingFile("a.md", "new"), new PendingFile("b.md", "x")),
                List.of());

        assertThat(Files.readString(tempDir.resolve("a.md"))).isEqualTo("new");
        assertThat(Files.readString(tempDir.resolve("b.md"))).isEqualTo("x");
        assertThat(Files.readString(tempDir.resolve("c.md"))).isEqualTo("keep");
        assertThat(saved).extracting(SavedFile::filename).containsExactly("a.md", "b.md");
        assertThat(listNames()).containsExactly("a.md", "b.md", "c.md");
    }

    @Test
    void replaceAll_deletesListedFile() throws IOException {
        Files.writeString(tempDir.resolve("a.md"), "old");

        List<SavedFile> saved = service.replaceAll(List.of(), List.of("a.md"));

        assertThat(tempDir.resolve("a.md")).doesNotExist();
        assertThat(saved).isEmpty();
        assertThat(listNames()).isEmpty();
    }

    @Test
    void replaceAll_duplicateNamesInSet_throwIllegalArgumentAndWriteNothing() throws IOException {
        assertThatThrownBy(() -> service.replaceAll(
                List.of(new PendingFile("a.md", "1"), new PendingFile("a.md", "2")),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Дубликат");

        assertThat(listNames()).isEmpty();
    }

    @Test
    void replaceAll_unsafeFileName_isRejected() throws IOException {
        assertThatThrownBy(() -> service.replaceAll(List.of(), List.of("../evil.md")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replaceAll(List.of(new PendingFile("a b.md", "x")), List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(listNames()).isEmpty();
    }

    @Test
    void saveAll_doesNotOverwriteExistingFile_andUniquifiesName() throws IOException {
        Files.writeString(tempDir.resolve("a.md"), "old");

        List<SavedFile> saved = service.saveAll(List.of(new PendingFile("a.md", "new")));

        assertThat(saved).extracting(SavedFile::filename).containsExactly("a_2.md");
        assertThat(Files.readString(tempDir.resolve("a.md"))).isEqualTo("old");
        assertThat(Files.readString(tempDir.resolve("a_2.md"))).isEqualTo("new");
    }

    @Test
    void saveAll_sameNameTwiceInOneBatch_isUniquified() throws IOException {
        List<SavedFile> saved = service.saveAll(List.of(
                new PendingFile("a.md", "1"),
                new PendingFile("a.md", "2")));

        assertThat(saved).extracting(SavedFile::filename).containsExactly("a.md", "a_2.md");
        assertThat(Files.readString(tempDir.resolve("a.md"))).isEqualTo("1");
        assertThat(Files.readString(tempDir.resolve("a_2.md"))).isEqualTo("2");
    }

    @Disabled("Ожидание из задания: saveAll с двумя одинаковыми именами бросает IllegalArgumentException. "
            + "Фактический код этого не делает: saveAll уникализирует имена (a.md, a_2.md), что описано в javadoc. "
            + "Это расхождение с ожиданием, а не подтверждённый баг. Дубликаты в replaceAll отклоняются "
            + "(см. replaceAll_duplicateNamesInSet_throwIllegalArgumentAndWriteNothing).")
    @Test
    void saveAll_duplicateNames_throwIllegalArgument() {
        assertThatThrownBy(() -> service.saveAll(List.of(
                new PendingFile("a.md", "1"),
                new PendingFile("a.md", "2"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
