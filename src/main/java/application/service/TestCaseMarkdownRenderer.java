package application.service;

import application.dto.Dtos.Clarification;
import application.dto.Dtos.ClarificationType;
import application.dto.Dtos.TestCase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TestCaseMarkdownRenderer {

    public String render(TestCase tc, List<Clarification> allClarifications) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(nullSafe(tc.id())).append(" — ").append(nullSafe(tc.title())).append("\n\n");
        sb.append("- **ID:** ").append(nullSafe(tc.id())).append("\n");
        sb.append("- **Тип:** ").append(nullSafe(tc.type())).append("\n");
        sb.append("- **Приоритет:** ").append(nullSafe(tc.priority())).append("\n");
        sb.append("- **Требование:** ").append(nullSafe(tc.requirementRef())).append("\n\n");

        List<Clarification> related = relatedClarifications(tc, allClarifications);
        if (!related.isEmpty()) {
            sb.append("## ⚠️ Требует уточнения\n\n");
            for (Clarification c : related) {
                // UNGROUNDED_ASSUMPTION в карточку не выводим — это не вопрос пользователю.
                if (c.type() == ClarificationType.UNGROUNDED_ASSUMPTION) {
                    continue;
                }
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

    public String renderClarifications(List<Clarification> clarifications) {
        StringBuilder sb = new StringBuilder("# Уточнения\n\n");
        if (clarifications == null || clarifications.isEmpty()) {
            sb.append("_Уточнений нет._\n");
            return sb.toString();
        }
        // В файл выводим только MISSING_REQUIREMENT.
        for (Clarification c : clarifications) {
            if (c.type() == ClarificationType.UNGROUNDED_ASSUMPTION) {
                continue;
            }
            sb.append("## ").append(nullSafe(c.requirementRef())).append("\n\n");
            sb.append("**Вопрос:** ").append(nullSafe(c.question())).append("\n\n");
            sb.append("**Причина:** ").append(nullSafe(c.reason())).append("\n\n---\n\n");
        }
        return sb.toString();
    }

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
                    .filter(c -> c.type() == ClarificationType.MISSING_REQUIREMENT)
                    .count();
            if (missing > 0) {
                sb.append("\n## ⚠️ Уточнения\n\n");
                for (Clarification c : clarifications) {
                    if (c.type() == ClarificationType.UNGROUNDED_ASSUMPTION) {
                        continue;
                    }
                    sb.append("- ").append(nullSafe(c.requirementRef())).append(": ")
                            .append(nullSafe(c.question())).append("\n");
                }
            }
        }
        return sb.toString();
    }

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

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
