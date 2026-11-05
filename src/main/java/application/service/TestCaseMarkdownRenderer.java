package application.service;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.ClarificationType;
import application.dto.Dtos.TestCase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Рендер Markdown-файлов прогона: отдельный файл на тест-кейс, {@code _clarifications.md} и
 * {@code _index.md}. Класс без состояния.
 * <p>
 * Уточнения типа {@code UNGROUNDED_ASSUMPTION} в файлах не выводятся: они попадают только в JSON-ответ API.
 */
@Service
public class TestCaseMarkdownRenderer {

    /**
     * Рендерит файл одного тест-кейса: заголовок, метаданные, блок уточнений для его требования,
     * предусловия, шаги и ожидаемый результат.
     * <p>
     * Блок уточнений выводится для уточнений с тем же {@code requirementRef}, что и у тест-кейса,
     * и с типом {@code MISSING_REQUIREMENT} или без типа. Пустой список шагов заменяется заглушкой.
     * {@code null} в полях выводится как пустая строка.
     *
     * @param tc               тест-кейс
     * @param allClarifications все уточнения прогона
     * @return текст Markdown
     */
    public String render(TestCase tc, List<Clarification> allClarifications) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(nullSafe(tc.id())).append(" — ").append(nullSafe(tc.title())).append("\n\n");
        sb.append("- **ID:** ").append(nullSafe(tc.id())).append("\n");
        sb.append("- **Тип:** ").append(nullSafe(tc.type())).append("\n");
        sb.append("- **Приоритет:** ").append(nullSafe(tc.priority())).append("\n");
        sb.append("- **Требование:** ").append(nullSafe(tc.requirementRef())).append("\n\n");

        List<Clarification> related = relatedClarifications(tc, allClarifications);
        List<Clarification> visible = related.stream()
                .filter(c -> c.type() == null || c.type() == ClarificationType.MISSING_REQUIREMENT)
                .toList();
        if (!visible.isEmpty()) {
            sb.append("## ⚠️ Требует уточнения\n\n");
            for (Clarification c : visible) {
                sb.append("> ⚠️ **Требует уточнения:** ").append(nullSafe(c.question())).append("\n");
                if (!nullSafe(c.reason()).isBlank() && !c.reason().equals(c.question())) {
                    sb.append(">\n> _Причина:_ ").append(nullSafe(c.reason())).append("\n");
                }
                if (!nullSafe(c.requirementRef()).isBlank()) {
                    sb.append(">\n> _Ссылка на требование:_ ").append(nullSafe(c.requirementRef())).append("\n");
                }
                sb.append("\n");
            }
        }

        if (tc.preconditions() != null && !tc.preconditions().isEmpty()) {
            sb.append("## Предусловия\n\n");
            for (String p : tc.preconditions()) {
                sb.append("- ").append(nullSafe(p)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Шаги\n\n");
        if (tc.steps() == null || tc.steps().isEmpty()) {
            sb.append("_Шаги не указаны._\n\n");
        } else {
            int i = 1;
            for (String step : tc.steps()) {
                sb.append(i++).append(". ").append(nullSafe(step)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("## Ожидаемый результат\n\n");
        sb.append(nullSafe(tc.expectedResult())).append("\n");
        return sb.toString();
    }

    /**
     * Рендерит файл {@code _clarifications.md}: по разделу на каждое уточнение, кроме
     * {@code UNGROUNDED_ASSUMPTION}. Если видимых уточнений нет, выводится строка «Уточнений нет».
     *
     * @param clarifications уточнения прогона; может быть {@code null}
     * @return текст Markdown
     */
    public String renderClarifications(List<Clarification> clarifications) {
        StringBuilder sb = new StringBuilder("# Уточнения\n\n");
        if (clarifications == null || clarifications.isEmpty()) {
            sb.append("_Уточнений нет._\n");
            return sb.toString();
        }
        boolean any = false;
        for (Clarification c : clarifications) {
            if (c.type() != null && c.type() == ClarificationType.UNGROUNDED_ASSUMPTION) {
                continue;
            }
            any = true;
            sb.append("## ").append(nullSafe(c.requirementRef())).append("\n\n");
            sb.append("**Вопрос:** ").append(nullSafe(c.question())).append("\n\n");
            sb.append("**Причина:** ").append(nullSafe(c.reason())).append("\n\n---\n\n");
        }
        if (!any) {
            sb.append("_Уточнений нет._\n");
        }
        return sb.toString();
    }

    /**
     * Рендерит {@code _index.md} по фактически сохранённым именам файлов.
     * <p>
     * Каждая строка содержит ссылку на файл тест-кейса (берётся из {@code filenameById}; если id
     * там нет, имя строится через {@link #fileNameFor}), заголовок, тип и приоритет. Если передан
     * {@code clarFilename}, добавляется раздел «⚠️ Уточнения» со ссылкой на файл уточнений и
     * списком уточнений, кроме {@code UNGROUNDED_ASSUMPTION}. Без файла уточнений раздел выводится
     * только при наличии уточнений типа {@code MISSING_REQUIREMENT} или без типа.
     *
     * @param testCases      тест-кейсы в порядке вывода; может быть {@code null}
     * @param clarifications уточнения прогона; может быть {@code null}
     * @param filenameById   id тест-кейса → реальное имя файла прогона; не должен быть {@code null}
     * @param clarFilename   реальное имя {@code _clarifications.md} или {@code null}, если файла нет
     * @return текст Markdown
     */
    public String renderIndexFromSaved(List<TestCase> testCases,
                                       List<Clarification> clarifications,
                                       Map<String, String> filenameById,
                                       String clarFilename) {
        StringBuilder sb = new StringBuilder("# Тест-кейсы — индекс\n\n");
        if (testCases != null) {
            for (TestCase tc : testCases) {
                String filename = filenameById.getOrDefault(tc.id(), fileNameFor(tc));
                sb.append("- [").append(nullSafe(tc.id())).append("](./")
                        .append(filename).append(") — ")
                        .append(nullSafe(tc.title()))
                        .append(" (`").append(nullSafe(tc.type())).append("`, ")
                        .append(nullSafe(tc.priority())).append(")\n");
            }
        }
        if (clarFilename != null) {
            sb.append("\n## ⚠️ Уточнения\n\n");
            sb.append("- [Уточнения](./").append(clarFilename).append(")\n");
            if (clarifications != null) {
                for (Clarification c : clarifications) {
                    if (c.type() != null && c.type() == ClarificationType.UNGROUNDED_ASSUMPTION) {
                        continue;
                    }
                    sb.append("- ").append(nullSafe(c.requirementRef())).append(": ")
                            .append(nullSafe(c.question())).append("\n");
                }
            }
        } else if (clarifications != null && !clarifications.isEmpty()) {
            long missing = clarifications.stream()
                    .filter(c -> c.type() == null || c.type() == ClarificationType.MISSING_REQUIREMENT)
                    .count();
            if (missing > 0) {
                sb.append("\n## ⚠️ Уточнения\n\n");
                for (Clarification c : clarifications) {
                    if (c.type() != null && c.type() == ClarificationType.UNGROUNDED_ASSUMPTION) {
                        continue;
                    }
                    sb.append("- ").append(nullSafe(c.requirementRef())).append(": ")
                            .append(nullSafe(c.question())).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Старая версия {@link #renderIndexFromSaved}, которая строит имена файлов из тест-кейсов.
     * Не учитывает уникализацию имён внутри прогона и поэтому может давать ссылки на файлы, которых нет в наборе.
     * Внутри модуля не вызывается.
     *
     * @param testCases      тест-кейсы
     * @param clarifications уточнения прогона
     * @return текст Markdown
     * @deprecated используйте {@link #renderIndexFromSaved} — старый метод даёт рассинхрон с сохранёнными файлами.
     */
    @Deprecated
    public String renderIndex(List<TestCase> testCases, List<Clarification> clarifications) {
        StringBuilder sb = new StringBuilder("# Тест-кейсы — индекс\n\n");
        if (testCases != null) {
            for (TestCase tc : testCases) {
                sb.append("- [").append(nullSafe(tc.id())).append("](./")
                        .append(fileNameFor(tc)).append(") — ")
                        .append(nullSafe(tc.title()))
                        .append(" (`").append(nullSafe(tc.type())).append("`, ")
                        .append(nullSafe(tc.priority())).append(")\n");
            }
        }
        if (clarifications != null && !clarifications.isEmpty()) {
            long missing = clarifications.stream()
                    .filter(c -> c.type() == null || c.type() == ClarificationType.MISSING_REQUIREMENT)
                    .count();
            if (missing > 0) {
                sb.append("\n## ⚠️ Уточнения\n\n");
                for (Clarification c : clarifications) {
                    if (c.type() != null && c.type() == ClarificationType.UNGROUNDED_ASSUMPTION) {
                        continue;
                    }
                    sb.append("- ").append(nullSafe(c.requirementRef())).append(": ")
                            .append(nullSafe(c.question())).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Строит имя файла тест-кейса в виде {@code <id>_<slug>.md}.
     * <p>
     * Id: символы вне {@code [a-zA-Z0-9_-]} заменяются на {@code _}; пустой id даёт {@code TC}.
     * Slug: заголовок в нижнем регистре; каждая последовательность символов, кроме латинских букв и цифр,
     * заменяется одним {@code _}; подчёркивания по краям убираются; ограничение 60 символов. Кириллица в slug не попадает,
     * поэтому тест-кейс с заголовком только на русском получает slug {@code test_case}.
     *
     * @param tc тест-кейс
     * @return имя файла без префикса прогона
     */
    public String fileNameFor(TestCase tc) {
        String id = nullSafe(tc.id()).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (id.isBlank()) id = "TC";
        String slug = nullSafe(tc.title())
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isBlank()) slug = "test_case";
        if (slug.length() > 60) slug = slug.substring(0, 60);
        return id + "_" + slug + ".md";
    }

    /**
     * Возвращает уточнения, относящиеся к тест-кейсу: с тем же {@code requirementRef}.
     * Тип уточнения не учитывается. Если у тест-кейса ссылка пустая, возвращается пустой список.
     *
     * @param tc  тест-кейс
     * @param all все уточнения прогона; может быть {@code null}
     * @return новый список (возможно пустой), в порядке исходного списка
     */
    public List<Clarification> relatedClarifications(TestCase tc, List<Clarification> all) {
        List<Clarification> result = new ArrayList<>();
        if (all == null || all.isEmpty()) return result;
        String ref = nullSafe(tc.requirementRef());
        for (Clarification c : all) {
            if (!ref.isBlank() && ref.equals(nullSafe(c.requirementRef()))) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * Заменяет {@code null} на пустую строку.
     *
     * @param s исходная строка, может быть {@code null}
     * @return {@code s} или пустая строка
     */
    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
