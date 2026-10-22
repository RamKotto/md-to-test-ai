package application.service;

import application.config.AppProperties;
import application.dto.Dtos.Clarification;
import application.dto.Dtos.ClarificationType;
import application.dto.Dtos.TestCase;
import application.exception.GenerationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncGenerationService {

    private static final Set<String> ALLOWED_TYPES =
            Set.of("UI", "API", "E2E", "NEGATIVE", "SECURITY", "PERFORMANCE");
    private static final Set<String> ALLOWED_PRIORITIES =
            Set.of("P0", "P1", "P2", "P3");

    private static final Pattern ID_PATTERN =
            Pattern.compile("^TC-(UI|API|E2E|NEG|NEGATIVE|SEC|SECURITY|PERF|PERFORMANCE)-\\d{3,}$");

    private static final Pattern HTTP_METHOD =
            Pattern.compile("\\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\b");

    private static final Pattern CONCRETE_HTTP_CODE =
            Pattern.compile("\\b([1-5]\\d{2})\\b");
    private static final Set<String> HTTP_CODE_WHITELIST =
            Set.of("200", "201", "204", "400", "401", "403", "404", "409", "422",
                    "429", "500", "502", "503");

    private static final Pattern CONCRETE_FIELD_NAME =
            Pattern.compile("\\b([a-z][a-z0-9]*_[a-z0-9_]+)\\b");
    private static final Set<String> FIELD_NAME_WHITELIST = Set.of(
            "utc_offset_seconds", "snake_case", "requirement_ref",
            "error_message", "error_code", "request_id", "response_time",
            "temperature_2m", "wind_speed_10m", "relative_humidity_2m",
            "minutely_15", "forecast_days", "best_match"
    );

    private static final Pattern CONCRETE_NUMBER_LIMIT =
            Pattern.compile("\\b(\\d{3,})\\b");

    private static final Pattern ABSOLUTE_URL =
            Pattern.compile("https?://[^\\s)\\]\"'`]+");

    /**
     * URL-фрагмент внутри текста — для изоляции от нормализации запятых.
     */
    private static final Pattern URL_IN_TEXT =
            Pattern.compile("https?://\\S+");

    private static final Pattern SPACED_NUMBER =
            Pattern.compile("\\b\\d{1,3}(?:[\\s\\u00A0]\\d{3})+\\b");

    /**
     * Query-параметр `name=value` — для точечного вырезания параметра целиком.
     */
    private static final Pattern QUERY_PARAM =
            Pattern.compile("([?&][a-zA-Z0-9_\\-]+)=([^&\\s\"'`]*)");

    private static final String NEUTRAL_EXPECTED_RESULT =
            "Поведение соответствует требованиям (см. уточнение)";
    private static final String NEUTRAL_STEP_SUFFIX =
            "(точное поведение уточняется)";
    private static final String NEUTRAL_NUMBER =
            "[значение из ответа на уточнение]";

    private final OpenRouterClient openRouter;
    private final ObjectMapper mapper;
    private final AppProperties appProps;

    public record GenerationResult(List<TestCase> testCases, List<Clarification> clarifications) {
    }

    public GenerationResult generate(String markdown) {
        String prompt = appProps.getPrompts().getGenerate()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String userText = markdown + "\n\n## Разрешённые конкретные значения (из документа)\n"
                + extractAllowedTokens(markdown);
        String response = openRouter.complete(prompt, userText);
        return parseResponse(response, markdown, null);
    }

    public GenerationResult answer(String markdown, String existingJson, String answersJson) {
        String prompt = appProps.getPrompts().getAnswers()
                .replace("{{schema}}", appProps.getPrompts().getJsonSchema());
        String userText = "## Документ\n" + markdown
                + "\n\n## Ранее сгенерированные тест-кейсы\n" + existingJson
                + "\n\n## Ответы на уточнения\n" + answersJson;
        String response = openRouter.complete(prompt, userText);
        return parseResponse(response, markdown, answersJson);
    }

    private GenerationResult parseResponse(String json, String sourceMarkdown, String groundingAnswers) {
        try {
            String cleaned = json.replaceAll("^```json\\s*", "")
                    .replaceAll("```\\s*$", "")
                    .trim();
            Map<String, Object> map = mapper.readValue(cleaned, new TypeReference<>() {
            });

            if (!map.containsKey("testCases") || !map.containsKey("clarifications")) {
                throw new GenerationException(
                        "Ответ модели не содержит обязательных ключей testCases/clarifications");
            }

            List<TestCase> rawCases = mapper.convertValue(
                    map.getOrDefault("testCases", List.of()),
                    new TypeReference<List<TestCase>>() {
                    });
            List<Clarification> rawClarifications = mapper.convertValue(
                    map.getOrDefault("clarifications", List.of()),
                    new TypeReference<List<Clarification>>() {
                    });

            List<Clarification> modelClarifications = rawClarifications.stream()
                    .map(c -> c.type() == null
                            ? Clarification.missing(c.requirementRef(), c.question(), c.reason())
                            : c)
                    .toList();

            List<TestCase> validated = validateAndRepairTestCases(rawCases);
            validateClarifications(modelClarifications);

            List<Clarification> groundingClarifications = new ArrayList<>();
            List<TestCase> sanitized = sanitizeAgainstDocument(
                    validated, sourceMarkdown, groundingAnswers, groundingClarifications);

            List<Clarification> merged = new ArrayList<>(modelClarifications);
            merged.addAll(groundingClarifications);
            List<Clarification> deduped = dedupeClarifications(merged);

            return new GenerationResult(sanitized, deduped);
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", json, e);
            throw new GenerationException("Модель вернула невалидный JSON: " + e.getMessage(), e);
        }
    }

    private List<TestCase> validateAndRepairTestCases(List<TestCase> cases) {
        Set<String> seenIds = new HashSet<>();
        List<TestCase> repaired = new ArrayList<>(cases.size());
        int autoSeq = 1;

        for (TestCase tc : cases) {
            String id = tc.id();
            if (isBlank(id) || !ID_PATTERN.matcher(id).matches()) {
                String type = safeType(tc.type());
                id = "TC-" + typeShort(type) + "-" + String.format("%03d", autoSeq++);
                log.warn("Некорректный id '{}' заменён на '{}'", tc.id(), id);
            }
            if (!seenIds.add(id)) {
                String type = safeType(tc.type());
                String newId;
                do {
                    newId = "TC-" + typeShort(type) + "-" + String.format("%03d", autoSeq++);
                } while (!seenIds.add(newId));
                log.warn("Дубликат id '{}' заменён на '{}'", id, newId);
                id = newId;
            }

            String type = tc.type();
            if (isBlank(type) || !ALLOWED_TYPES.contains(type)) {
                log.warn("Недопустимый type '{}' для {} — заменён на UI", type, id);
                type = "UI";
            }

            String title = nullSafe(tc.title());
            if (title.isBlank()) {
                throw new GenerationException("Некорректный title для " + id);
            }
            if (title.length() > 200) {
                title = title.substring(0, 200).trim();
            }

            if (isBlank(tc.requirementRef())) {
                throw new GenerationException("requirementRef пуст для " + id);
            }

            List<String> preconditions = tc.preconditions() == null
                    ? List.of() : tc.preconditions();

            if (tc.steps() == null || tc.steps().isEmpty()) {
                throw new GenerationException("steps пуст для " + id);
            }
            if (isBlank(tc.expectedResult())) {
                throw new GenerationException("expectedResult пуст для " + id);
            }

            String priority = tc.priority();
            if (priority == null || !ALLOWED_PRIORITIES.contains(priority)) {
                log.warn("Некорректный priority '{}' для {} — заменён на P2", priority, id);
                priority = "P2";
            }

            repaired.add(new TestCase(id, type, title, tc.requirementRef(),
                    preconditions, tc.steps(), tc.expectedResult(), priority));
        }
        return repaired;
    }

    private void validateClarifications(List<Clarification> clarifications) {
        for (Clarification c : clarifications) {
            if (isBlank(c.requirementRef())) {
                throw new GenerationException("Пустой requirementRef в clarification");
            }
            if (isBlank(c.question())) {
                throw new GenerationException("Пустой question в clarification для " + c.requirementRef());
            }
        }
    }

    private List<TestCase> sanitizeAgainstDocument(
            List<TestCase> cases,
            String sourceMarkdown,
            String groundingAnswers,
            List<Clarification> outClarifications) {

        boolean answersProvided = groundingAnswers != null && !groundingAnswers.isBlank();
        if (sourceMarkdown == null || sourceMarkdown.isBlank()) {
            return cases;
        }
        String docLower = sourceMarkdown.toLowerCase(Locale.ROOT);
        String answersLower = answersProvided
                ? groundingAnswers.toLowerCase(Locale.ROOT)
                : null;
        Set<String> clarificationRefsAdded = new HashSet<>();
        List<TestCase> result = new ArrayList<>(cases.size());

        for (TestCase tc : cases) {
            String ref = nullSafe(tc.requirementRef());
            String requirementText = extractRequirementBlock(sourceMarkdown, ref);
            String refLower = requirementText == null
                    ? null
                    : requirementText.toLowerCase(Locale.ROOT);

            boolean isNegative = "NEGATIVE".equalsIgnoreCase(nullSafe(tc.type()));

            Set<String> ungrounded = new LinkedHashSet<>();

            String sanitizedExpected = sanitizeText(
                    nullSafe(tc.expectedResult()), docLower, refLower, answersLower,
                    ungrounded, /*isStep*/ false, isNegative, tc.type());
            if (sanitizedExpected.isBlank()) {
                sanitizedExpected = NEUTRAL_EXPECTED_RESULT;
            }

            List<String> sanitizedSteps = new ArrayList<>();
            if (tc.steps() != null) {
                for (String step : tc.steps()) {
                    // FIX (баг 5, 6): в NEGATIVE-шагах числа — это тестовые данные,
                    // их нельзя вырезать, иначе latitude=100 превращается в latitude=.
                    String sanitized = isNegative
                            ? nullSafe(step)
                            : sanitizeText(nullSafe(step), docLower, refLower, answersLower,
                            ungrounded, /*isStep*/ true, false, tc.type());
                    if (sanitized.isBlank()) {
                        sanitized = NEUTRAL_STEP_SUFFIX.trim();
                    }
                    sanitizedSteps.add(sanitized);
                }
            }

            if (!ungrounded.isEmpty()) {
                log.warn("Тест-кейс {}: вырезаны неподтверждённые утверждения {} (требование {})",
                        tc.id(), ungrounded, ref);
                if (!answersProvided && clarificationRefsAdded.add(ref)) {
                    outClarifications.add(new Clarification(
                            ref,
                            "Уточните ожидаемое поведение: в документе нет утверждений "
                                    + String.join(", ", ungrounded)
                                    + ", но они были указаны в тест-кейсе",
                            "Модель домыслила конкретику, отсутствующую в требовании "
                                    + ref + "; тест-кейс санитизирован",
                            ClarificationType.UNGROUNDED_ASSUMPTION));
                }
            }

            result.add(new TestCase(
                    tc.id(), tc.type(), tc.title(), tc.requirementRef(),
                    tc.preconditions(), sanitizedSteps, sanitizedExpected, tc.priority()));
        }
        return result;
    }

    private String sanitizeText(String text, String docLower, String refLower,
                                String answersLower, Set<String> ungrounded,
                                boolean isStep, boolean isNegative, String type) {
        if (text.isBlank()) {
            return text;
        }
        String out = text;

        // FIX (баг 3): сначала выделяем URL-фрагменты, чтобы нормализация запятых
        // не превращала latitude=52.52,48.85 в latitude=52.52, 48.85.
        List<String> protectedUrls = new ArrayList<>();
        Matcher urlM = URL_IN_TEXT.matcher(out);
        StringBuilder withPlaceholders = new StringBuilder();
        int last = 0;
        while (urlM.find()) {
            withPlaceholders.append(out, last, urlM.start());
            withPlaceholders.append("__URL_").append(protectedUrls.size()).append("__");
            protectedUrls.add(urlM.group());
            last = urlM.end();
        }
        withPlaceholders.append(out.substring(last));
        String working = withPlaceholders.toString();

        // FIX (баг 4): вырезаем query-параметр целиком, если значение ungrounded.
        working = stripUngroundedQueryParams(working, docLower, refLower, answersLower, ungrounded);

        // HTTP-коды
        final String currentOut1 = working;
        working = replaceUngrounded(working, CONCRETE_HTTP_CODE, 1, docLower, refLower, answersLower,
                ungrounded,
                token -> "HTTP " + token,
                token -> HTTP_CODE_WHITELIST.contains(token) || isGroundedHttpContext(currentOut1, token));

        // snake_case-поля
        working = replaceUngrounded(working, CONCRETE_FIELD_NAME, 1, docLower, refLower, answersLower,
                ungrounded,
                token -> "поле '" + token + "'",
                FIELD_NAME_WHITELIST::contains);

        // Числа: 3+ цифр
        // FIX (баг 1, 6): для чисел заменяем на NEUTRAL_NUMBER, а не пустоту,
        // чтобы не получалось "равно ," / "- до". И grounding с арифметическими производными.
        working = replaceUngrounded(working, CONCRETE_NUMBER_LIMIT, 1, docLower, refLower, answersLower,
                ungrounded,
                token -> "число " + token,
                token -> isArithmeticDerivative(token, docLower, refLower, answersLower),
                NEUTRAL_NUMBER);

        // Числа с пробелом
        working = replaceUngroundedNormalized(working, SPACED_NUMBER, docLower, refLower, answersLower,
                ungrounded, token -> "число " + token, NEUTRAL_NUMBER);

        // FIX (баг 8): UI-тексты проверяем по кавычкам «...» / "...".
        if ("UI".equalsIgnoreCase(nullSafe(type))) {
            working = sanitizeUiQuotes(working, docLower, refLower, answersLower, ungrounded);
        }

        // Восстанавливаем URL
        for (int i = 0; i < protectedUrls.size(); i++) {
            working = working.replace("__URL_" + i + "__", protectedUrls.get(i));
        }

        if (!ungrounded.isEmpty()) {
            working = normalizeSpacesOnlyOutsideUrls(working);
        }
        return working;
    }

    /**
     * FIX (баг 4): `?models=unknown_model` → весь параметр вырезается,
     * а не `?models=`. Иначе получается невалидный запрос.
     */
    private String stripUngroundedQueryParams(String text, String docLower, String refLower,
                                              String answersLower, Set<String> ungrounded) {
        Matcher m = QUERY_PARAM.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String name = m.group(1);       // "?models" или "&models"
            String value = m.group(2);
            if (value == null || value.isBlank()) {
                continue;
            }
            String valueLower = value.toLowerCase(Locale.ROOT);
            boolean grounded = containsToken(docLower, valueLower)
                    || containsToken(refLower, valueLower)
                    || (answersLower != null && containsToken(answersLower, valueLower));
            if (grounded) {
                continue;
            }
            ungrounded.add("параметр " + name.substring(1) + "=" + value);
            sb.append(text, last, m.start());
            last = m.end();
            changed = true;
        }
        if (!changed) return text;
        sb.append(text.substring(last));
        return sb.toString();
    }

    /**
     * FIX (баг 1): число grounded, если оно есть в документе ИЛИ является
     * производным (N*24, N*7, N*60) от числа, которое есть в документе.
     */
    private boolean isArithmeticDerivative(String token, String docLower, String refLower,
                                           String answersLower) {
        if (containsToken(docLower, token)
                || containsToken(refLower, token)
                || (answersLower != null && containsToken(answersLower, token))) {
            return true;
        }
        try {
            long n = Long.parseLong(token);
            for (long base : new long[]{n / 24, n / 7, n / 60}) {
                if (base <= 0) continue;
                if (base * 24 == n || base * 7 == n || base * 60 == n) {
                    String b = Long.toString(base);
                    if (containsToken(docLower, b)
                            || containsToken(refLower, b)
                            || (answersLower != null && containsToken(answersLower, b))) {
                        return true;
                    }
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return false;
    }

    /**
     * FIX (баг 8): в UI-кейсах оставляем только цитаты в «...» / "...",
     * которые реально есть в документе. Остальной свободный текст — вырезаем.
     */
    private String sanitizeUiQuotes(String text, String docLower, String refLower,
                                    String answersLower, Set<String> ungrounded) {
        Pattern quote = Pattern.compile("[«\"]([^»\"]{2,200})[»\"]");
        Matcher m = quote.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            String quoted = m.group(1);
            String qLower = quoted.toLowerCase(Locale.ROOT);
            boolean grounded = docLower.contains(qLower)
                    || (refLower != null && refLower.contains(qLower))
                    || (answersLower != null && answersLower.contains(qLower));
            if (grounded) {
                sb.append(m.group());
            } else {
                ungrounded.add("цитата «" + quoted + "»");
                sb.append("«…»");
            }
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    private boolean isGroundedHttpContext(String text, String code) {
        // FIX (баг 7): «HTTP NNN» без предлога тоже считается grounded.
        Pattern p = Pattern.compile(
                "(?i)(?:HTTP\\s*|(?:http|статус|status|ошибк|error|ответ|response|код).{0,40}\\b)"
                        + Pattern.quote(code) + "\\b|\\b" + Pattern.quote(code)
                        + "\\b.{0,40}(?:http|статус|status|ошибк|error|ответ|response|код)");
        return p.matcher(text).find();
    }

    private String replaceUngrounded(
            String text, Pattern pattern, int group, String docLower, String refLower,
            String answersLower, Set<String> ungrounded,
            java.util.function.Function<String, String> description,
            java.util.function.Predicate<String> whitelist) {
        return replaceUngrounded(text, pattern, group, docLower, refLower, answersLower,
                ungrounded, description, whitelist, "");
    }

    private String replaceUngrounded(
            String text, Pattern pattern, int group, String docLower, String refLower,
            String answersLower, Set<String> ungrounded,
            java.util.function.Function<String, String> description,
            java.util.function.Predicate<String> whitelist,
            String replacement) {

        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String token = m.group(group);
            String tokenLower = token.toLowerCase(Locale.ROOT);
            boolean grounded = whitelist.test(token)
                    || containsToken(docLower, tokenLower)
                    || containsToken(refLower, tokenLower)
                    || (answersLower != null && containsToken(answersLower, tokenLower));
            if (grounded) {
                continue;
            }
            ungrounded.add(description.apply(token));
            sb.append(text, last, m.start());
            if (replacement != null && !replacement.isEmpty()) {
                sb.append(replacement);
            }
            last = m.end();
            changed = true;
        }
        if (!changed) {
            return text;
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    private String replaceUngroundedNormalized(
            String text, Pattern pattern, String docLower, String refLower,
            String answersLower, Set<String> ungrounded,
            java.util.function.Function<String, String> description,
            String replacement) {

        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            String token = m.group();
            String normalized = token.replaceAll("[\\s\\u00A0]", "");
            String normLower = normalized.toLowerCase(Locale.ROOT);
            boolean grounded = containsToken(docLower, normLower)
                    || containsToken(refLower, normLower)
                    || (answersLower != null && containsToken(answersLower, normLower));
            if (grounded) {
                continue;
            }
            ungrounded.add(description.apply(token));
            sb.append(text, last, m.start());
            if (replacement != null && !replacement.isEmpty()) {
                sb.append(replacement);
            }
            last = m.end();
            changed = true;
        }
        if (!changed) {
            return text;
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /**
     * FIX (баг 3): нормализуем запятые и пробелы ТОЛЬКО вне URL-фрагментов.
     */
    private String normalizeSpacesOnlyOutsideUrls(String text) {
        Matcher m = URL_IN_TEXT.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String plain = text.substring(last, m.start());
            sb.append(plain.replaceAll("\\s*,\\s*", ", ")
                    .replaceAll("\\s{2,}", " ")
                    .replaceAll("\\s+([.,;:])", "$1")
                    .replaceAll("^[\\s,;:]+", "")
                    .replaceAll("[\\s,;:]+$", ""));
            sb.append(m.group());
            last = m.end();
        }
        String tail = text.substring(last)
                .replaceAll("\\s*,\\s*", ", ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([.,;:])", "$1")
                .replaceAll("^[\\s,;:]+", "")
                .replaceAll("[\\s,;:]+$", "");
        sb.append(tail);
        return sb.toString().trim();
    }

    private String extractRequirementBlock(String markdown, String ref) {
        if (ref.isBlank() || "N/A".equalsIgnoreCase(ref)) {
            return null;
        }
        String[] lines = markdown.split("\n", -1);
        int startLine = -1;
        int headingLevel = -1;
        Pattern headingWithRef = Pattern.compile(
                "^(#{1,6})\\s+.*" + Pattern.quote(ref) + ".*$");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = headingWithRef.matcher(lines[i]);
            if (m.find()) {
                startLine = i;
                headingLevel = m.group(1).length();
                break;
            }
        }
        if (startLine < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i < lines.length; i++) {
            if (i > startLine) {
                Matcher m = Pattern.compile("^(#{1,6})\\s").matcher(lines[i]);
                if (m.find() && m.group(1).length() <= headingLevel) {
                    break;
                }
            }
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    private boolean containsToken(String haystackLower, String tokenLower) {
        if (haystackLower == null) {
            return false;
        }
        Pattern p = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(tokenLower) + "(?![a-z0-9_])");
        return p.matcher(haystackLower).find();
    }

    private List<Clarification> dedupeClarifications(List<Clarification> src) {
        Map<String, List<Clarification>> byRef = new LinkedHashMap<>();
        for (Clarification c : src) {
            byRef.computeIfAbsent(c.requirementRef(), k -> new ArrayList<>()).add(c);
        }
        List<Clarification> out = new ArrayList<>(byRef.size());
        for (Map.Entry<String, List<Clarification>> e : byRef.entrySet()) {
            List<Clarification> group = e.getValue();
            Clarification primary = group.stream()
                    .filter(c -> c.type() == ClarificationType.MISSING_REQUIREMENT)
                    .findFirst()
                    .orElse(group.get(0));
            String details = group.size() > 1
                    ? "Дополнительные детали: " + group.stream()
                    .filter(c -> c != primary)
                    .map(Clarification::question)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("")
                    : primary.reason();
            out.add(new Clarification(e.getKey(), primary.question(), details, primary.type()));
        }
        return out;
    }

    private String extractAllowedTokens(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "(пусто)";
        }
        Set<String> urls = new TreeSet<>();
        Set<String> methods = new TreeSet<>();
        Set<String> httpCodes = new TreeSet<>();
        Set<String> fields = new TreeSet<>();
        Set<String> numbers = new TreeSet<>();

        Matcher m = Pattern.compile("(/[a-zA-Z0-9_\\-./{}]+)").matcher(markdown);
        while (m.find()) {
            String u = m.group(1);
            if (u.length() > 1 && !u.endsWith("//")) urls.add(u);
        }
        m = ABSOLUTE_URL.matcher(markdown);
        while (m.find()) urls.add(m.group());

        m = HTTP_METHOD.matcher(markdown);
        while (m.find()) methods.add(m.group(1));
        m = CONCRETE_HTTP_CODE.matcher(markdown);
        while (m.find()) {
            if (HTTP_CODE_WHITELIST.contains(m.group(1))) httpCodes.add(m.group(1));
        }
        m = CONCRETE_FIELD_NAME.matcher(markdown);
        while (m.find()) fields.add(m.group(1));
        m = CONCRETE_NUMBER_LIMIT.matcher(markdown);
        while (m.find()) numbers.add(m.group(1));
        m = SPACED_NUMBER.matcher(markdown);
        while (m.find()) {
            numbers.add(m.group().replaceAll("[\\s\\u00A0]", ""));
        }

        // FIX (баг 1): добавляем арифметические производные, чтобы 168 (=7*24)
        // считалось grounded, если в документе есть 7.
        Set<String> derived = new TreeSet<>();
        for (String n : new ArrayList<>(numbers)) {
            try {
                long v = Long.parseLong(n);
                derived.add(Long.toString(v * 24));
                derived.add(Long.toString(v * 7));
                derived.add(Long.toString(v * 60));
            } catch (NumberFormatException ignored) {
            }
        }
        numbers.addAll(derived);

        StringBuilder sb = new StringBuilder();
        sb.append("- URL: ").append(urls.isEmpty() ? "(нет)" : urls).append('\n');
        sb.append("- HTTP-методы: ").append(methods.isEmpty() ? "(нет)" : methods).append('\n');
        sb.append("- HTTP-коды: ").append(httpCodes.isEmpty() ? "(нет)" : httpCodes).append('\n');
        sb.append("- Имена полей: ").append(fields.isEmpty() ? "(нет)" : fields).append('\n');
        sb.append("- Числа: ").append(numbers.isEmpty() ? "(нет)" : numbers).append('\n');
        sb.append("Используй эти значения для конкретики (URL, коды, имена полей, лимиты). ")
                .append("Для UI-проверок разрешено цитировать ТЕКСТ из документа (заголовки, подписи, ")
                .append("сообщения) — это не домысел, а цитата. Покрывай ВСЕ разделы документа ")
                .append("(UI, API, NEGATIVE, E2E), а не только API.");
        return sb.toString();
    }

    private String safeType(String type) {
        return type == null || !ALLOWED_TYPES.contains(type) ? "UI" : type;
    }

    private String typeShort(String type) {
        return switch (type) {
            case "NEGATIVE" -> "NEG";
            case "SECURITY" -> "SEC";
            case "PERFORMANCE" -> "PERF";
            default -> type;
        };
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
