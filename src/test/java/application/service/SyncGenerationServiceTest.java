package application.service;

import application.config.AppProperties;
import application.dto.Dtos.ClarificationType;
import application.dto.Dtos.TestCase;
import application.exception.GenerationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты разбора и валидации ответа модели {@link SyncGenerationService}.
 * {@link OpenRouterClient} заменён моком, поэтому тесты не обращаются к сети.
 */
@ExtendWith(MockitoExtension.class)
class SyncGenerationServiceTest {

    private static final String MARKDOWN = """
            # Требования

            ## REQ-1 Вход

            Пользователь открывает страницу входа и нажимает кнопку Войти.
            """;

    private static final String VALID_CASE_1 = """
            {"id":"TC-UI-001","type":"UI","title":"Открытие страницы входа","requirementRef":"REQ-1",
             "preconditions":[],"steps":["Открыть страницу входа"],
             "expectedResult":"Страница входа открыта","priority":"P1"}
            """;

    private static final String VALID_CASE_2 = """
            {"id":"TC-UI-006","type":"UI","title":"Нажатие кнопки Войти","requirementRef":"REQ-1",
             "preconditions":[],"steps":["Нажать кнопку Войти"],
             "expectedResult":"Вход выполнен","priority":"P2"}
            """;

    @Mock
    OpenRouterClient openRouter;

    private SyncGenerationService service;

    @BeforeEach
    void setUp() {
        AppProperties appProps = new AppProperties();
        appProps.getPrompts().setGenerate("x");
        appProps.getPrompts().setJsonSchema("{}");
        appProps.getPrompts().setAnswers("y");
        service = new SyncGenerationService(openRouter, new ObjectMapper(), appProps);
    }

    /**
     * Настраивает мок OpenRouter так, чтобы любой вызов {@code complete} возвращал {@code json}.
     *
     * @param json ответ модели, который получит сервис
     */
    private void modelReturns(String json) {
        when(openRouter.complete(anyString(), anyString())).thenReturn(json);
    }

    @Test
    void generate_sendsPromptAndMarkdownToOpenRouter() {
        modelReturns("{\"testCases\":[],\"clarifications\":[]}");

        service.generate(MARKDOWN);

        verify(openRouter).complete(eq("x"), startsWith(MARKDOWN));
    }

    @Test
    void invalidCases_emptyTitleStepsOrExpectedResult_areDropped_othersKept() {
        modelReturns("""
                {"testCases":[
                  %s,
                  {"id":"TC-UI-002","type":"UI","title":"","requirementRef":"REQ-1",
                   "steps":["Нажать кнопку Войти"],"expectedResult":"Вход выполнен","priority":"P1"},
                  {"id":"TC-UI-003","type":"UI","title":"Без шагов","requirementRef":"REQ-1",
                   "steps":[],"expectedResult":"Вход выполнен","priority":"P1"},
                  {"id":"TC-UI-004","type":"UI","title":"Только пустые шаги","requirementRef":"REQ-1",
                   "steps":["   "],"expectedResult":"Вход выполнен","priority":"P1"},
                  {"id":"TC-UI-005","type":"UI","title":"Без ожидаемого результата","requirementRef":"REQ-1",
                   "steps":["Нажать кнопку Войти"],"expectedResult":"  ","priority":"P1"},
                  %s
                ],"clarifications":[]}
                """.formatted(VALID_CASE_1, VALID_CASE_2));

        var result = service.generate(MARKDOWN);

        assertThat(result.testCases())
                .extracting(TestCase::id)
                .containsExactly("TC-UI-001", "TC-UI-006");
    }

    @Test
    void emptyOrMissingRequirementRef_isReplacedWithNA() {
        modelReturns("""
                {"testCases":[
                  {"id":"TC-UI-001","type":"UI","title":"Пустая ссылка","requirementRef":"",
                   "steps":["Открыть страницу входа"],"expectedResult":"Страница открыта","priority":"P2"},
                  {"id":"TC-UI-002","type":"UI","title":"Ссылки нет вовсе",
                   "steps":["Открыть страницу входа"],"expectedResult":"Страница открыта","priority":"P2"},
                  {"id":"TC-UI-003","type":"UI","title":"Со ссылкой","requirementRef":"REQ-1",
                   "steps":["Открыть страницу входа"],"expectedResult":"Страница открыта","priority":"P2"}
                ],"clarifications":[]}
                """);

        var result = service.generate(MARKDOWN);

        assertThat(result.testCases())
                .extracting(TestCase::requirementRef)
                .containsExactly("N/A", "N/A", "REQ-1");
    }

    @Test
    void allCasesInvalid_throwsGenerationException() {
        modelReturns("""
                {"testCases":[
                  {"id":"TC-UI-001","type":"UI","title":"","steps":["Открыть страницу входа"],
                   "expectedResult":"Страница открыта"},
                  {"id":"TC-UI-002","type":"UI","title":"Без шагов","steps":[],
                   "expectedResult":"Страница открыта"}
                ],"clarifications":[]}
                """);

        assertThatThrownBy(() -> service.generate(MARKDOWN))
                .isInstanceOf(GenerationException.class);
    }

    @Test
    void responseWithoutRequiredKeys_throwsGenerationException() {
        modelReturns("{\"testCases\":[" + VALID_CASE_1 + "]}");

        assertThatThrownBy(() -> service.generate(MARKDOWN))
                .isInstanceOf(GenerationException.class);
    }

    @Test
    void clarificationsWithoutRefOrQuestion_areDropped() {
        modelReturns("""
                {"testCases":[%s],
                 "clarifications":[
                   {"requirementRef":"","question":"Что будет при пустом поле?","reason":"нет данных","type":"MISSING_REQUIREMENT"},
                   {"requirementRef":"REQ-1","question":"","reason":"нет данных","type":"MISSING_REQUIREMENT"},
                   {"reason":"без ссылки и вопроса"},
                   {"requirementRef":"REQ-1","question":"Какое сообщение при неверном пароле?","reason":"в документе не описано","type":"MISSING_REQUIREMENT"}
                 ]}
                """.formatted(VALID_CASE_1));

        var result = service.generate(MARKDOWN);

        assertThat(result.clarifications()).singleElement().satisfies(c -> {
            assertThat(c.requirementRef()).isEqualTo("REQ-1");
            assertThat(c.question()).isEqualTo("Какое сообщение при неверном пароле?");
            assertThat(c.type()).isEqualTo(ClarificationType.MISSING_REQUIREMENT);
        });
    }

    @Test
    void clarificationWithoutType_isTreatedAsMissingRequirement() {
        modelReturns("""
                {"testCases":[%s],
                 "clarifications":[{"requirementRef":"REQ-1","question":"Какой срок хранения?","reason":"нет"}]}
                """.formatted(VALID_CASE_1));

        var result = service.generate(MARKDOWN);

        assertThat(result.clarifications()).singleElement().satisfies(c -> {
            assertThat(c.question()).isEqualTo("Какой срок хранения?");
            assertThat(c.type()).isEqualTo(ClarificationType.MISSING_REQUIREMENT);
        });
    }

    @Test
    void responseWrappedInFences_isUnwrapped_forJsonAndBareFence() {
        String body = "{\"testCases\":[" + VALID_CASE_1 + "],\"clarifications\":[]}";

        modelReturns("```json\n" + body + "\n```");
        assertThat(service.generate(MARKDOWN).testCases()).hasSize(1);

        modelReturns("```\n" + body + "\n```");
        assertThat(service.generate(MARKDOWN).testCases()).hasSize(1);

        modelReturns("  ```JSON\n" + body + "\n```  \n");
        assertThat(service.generate(MARKDOWN).testCases()).hasSize(1);
    }

    @Test
    void spacesAroundUrl_areKeptWhenFragmentIsSanitized() {
        modelReturns("""
                {"testCases":[
                  {"id":"TC-UI-001","type":"UI","title":"Переход по ссылке","requirementRef":"REQ-1",
                   "steps":["Открыть страницу входа"],
                   "expectedResult":"Открыть https://example.com/a и проверить 777","priority":"P2"}
                ],"clarifications":[]}
                """);

        var result = service.generate(MARKDOWN);

        assertThat(result.testCases()).singleElement().satisfies(tc ->
                assertThat(tc.expectedResult())
                        .isEqualTo("Открыть https://example.com/a и проверить [значение из ответа на уточнение]"));
    }

    @Test
    void ungroundedQueryParamsInAbsoluteUrl_areRemoved_groundedKept() {
        modelReturns("""
                {"testCases":[
                  {"id":"TC-UI-001","type":"UI","title":"Переход по ссылке","requirementRef":"REQ-1",
                   "steps":["Открыть страницу входа"],
                   "expectedResult":"Переход по https://example.com/login?mode=turbo&action=Войти выполнен","priority":"P2"}
                ],"clarifications":[]}
                """);

        var result = service.generate(MARKDOWN);

        assertThat(result.testCases()).singleElement().satisfies(tc ->
                assertThat(tc.expectedResult())
                        .isEqualTo("Переход по https://example.com/login?action=Войти выполнен"));
    }

    /**
     * Два вопроса типа MISSING_REQUIREMENT по одному требованию сливаются: первый сохраняет reason,
     * второй уходит в «Дополнительные детали».
     */
    @Test
    void clarificationsForSameRequirement_keepPrimaryReason_andAppendExtraQuestions() {
        modelReturns("""
                {"testCases":[%s],
                 "clarifications":[
                   {"requirementRef":"REQ-1","question":"Какое сообщение при неверном пароле?","reason":"в документе не описано","type":"MISSING_REQUIREMENT"},
                   {"requirementRef":"REQ-1","question":"Сколько попыток входа допустимо?","reason":"нет данных","type":"MISSING_REQUIREMENT"}
                 ]}
                """.formatted(VALID_CASE_1));

        var result = service.generate(MARKDOWN);

        assertThat(result.clarifications()).singleElement().satisfies(c -> {
            assertThat(c.question()).isEqualTo("Какое сообщение при неверном пароле?");
            assertThat(c.reason()).isEqualTo(
                    "в документе не описано; Дополнительные детали: Сколько попыток входа допустимо?");
            assertThat(c.type()).isEqualTo(ClarificationType.MISSING_REQUIREMENT);
        });
    }

    /**
     * UNGROUNDED_ASSUMPTION не сливается с вопросом MISSING_REQUIREMENT по тому же требованию:
     * тип сохраняется, запись остаётся отдельной.
     */
    @Test
    void clarificationsForSameRequirement_ungroundedNotMerged_typeKept() {
        modelReturns("""
                {"testCases":[%s],
                 "clarifications":[
                   {"requirementRef":"REQ-1","question":"Какое сообщение при неверном пароле?","reason":"в документе не описано","type":"MISSING_REQUIREMENT"},
                   {"requirementRef":"REQ-1","question":"Сколько попыток входа допустимо?","reason":"нет данных","type":"UNGROUNDED_ASSUMPTION"}
                 ]}
                """.formatted(VALID_CASE_1));

        var result = service.generate(MARKDOWN);

        assertThat(result.clarifications()).hasSize(2);
        assertThat(result.clarifications().get(0)).satisfies(c -> {
            assertThat(c.question()).isEqualTo("Какое сообщение при неверном пароле?");
            assertThat(c.reason()).isEqualTo("в документе не описано");
            assertThat(c.type()).isEqualTo(ClarificationType.MISSING_REQUIREMENT);
        });
        assertThat(result.clarifications().get(1)).satisfies(c -> {
            assertThat(c.question()).isEqualTo("Сколько попыток входа допустимо?");
            assertThat(c.type()).isEqualTo(ClarificationType.UNGROUNDED_ASSUMPTION);
        });
    }

    /**
     * Код 418 нет в документе: контекст «Статус» не делает его обоснованным, код заменяется описанием.
     */
    @Test
    void httpCodeNotInDocument_isReplacedEvenWithContextWord() {
        modelReturns("""
                {"testCases":[
                  {"id":"TC-UI-001","type":"UI","title":"Статус ответа","requirementRef":"REQ-1",
                   "steps":["Открыть страницу входа"],"expectedResult":"Статус 418 отображается","priority":"P2"}
                ],"clarifications":[]}
                """);

        var result = service.generate(MARKDOWN);

        assertThat(result.testCases()).singleElement().satisfies(tc ->
                assertThat(tc.expectedResult())
                        .isEqualTo("Статус [код ответа уточняется] отображается"));
    }

    /**
     * Код 418 есть в блоке требования: обоснован, остаётся в тексте.
     */
    @Test
    void httpCodeFromDocument_isKept() {
        String markdown = """
                # Требования

                ## REQ-2 Статус

                Сервис отвечает кодом 418 на запрос чайника.
                """;
        modelReturns("""
                {"testCases":[
                  {"id":"TC-UI-001","type":"UI","title":"Статус ответа","requirementRef":"REQ-2",
                   "steps":["Открыть страницу входа"],"expectedResult":"Статус 418 отображается","priority":"P2"}
                ],"clarifications":[]}
                """);

        var result = service.generate(markdown);

        assertThat(result.testCases()).singleElement()
                .satisfies(tc -> assertThat(tc.expectedResult()).isEqualTo("Статус 418 отображается"));
        assertThat(result.clarifications()).isEmpty();
    }

    /**
     * Распространённый код 200 без связи с документом не проходит: белого списка больше нет.
     */
    @Test
    void http200NotInDocument_isReplaced() {
        modelReturns("""
                {"testCases":[
                  {"id":"TC-UI-001","type":"UI","title":"Успешный ответ","requirementRef":"REQ-1",
                   "steps":["Открыть страницу входа"],"expectedResult":"Сервер возвращает 200 при успехе","priority":"P2"}
                ],"clarifications":[]}
                """);

        var result = service.generate(MARKDOWN);

        assertThat(result.testCases()).singleElement()
                .satisfies(tc -> assertThat(tc.expectedResult())
                        .isEqualTo("Сервер возвращает [код ответа уточняется] при успехе"));
    }

    /**
     * В NEGATIVE-шаге входное значение (число за пределом лимита) остаётся тестовыми данными,
     * а HTTP-код, которого нет в документе, по-прежнему заменяется.
     */
    @Test
    void negativeSteps_inputValuesKept_httpCodeStillChecked() {
        modelReturns("""
                {"testCases":[
                  {"id":"TC-NEG-001","type":"NEGATIVE","title":"Слишком длинное имя","requirementRef":"REQ-1",
                   "preconditions":[],"steps":["Ввести имя из 1001 символа","Отправить форму, сервер отвечает 418"],
                   "expectedResult":"Система отклоняет запрос","priority":"P1"}
                ],"clarifications":[]}
                """);

        var result = service.generate(MARKDOWN);

        assertThat(result.testCases()).singleElement().satisfies(tc -> {
            assertThat(tc.steps()).containsExactly(
                    "Ввести имя из 1001 символа",
                    "Отправить форму, сервер отвечает [код ответа уточняется]");
            assertThat(tc.expectedResult()).isEqualTo("Система отклоняет запрос");
        });
    }

    /**
     * Регулярка путей не берёт «/» внутри слов и дробей: «and/or» и «24/7» не попадают в подсказку URL.
     */
    @Test
    void allowedTokens_pathRegexIgnoresSlashInsideWords() {
        String markdown = """
                # Требования

                ## REQ-1 Поиск

                Режим and/or, нагрузка 24/7, запрос GET /users/{id} к сервису.
                """;
        modelReturns("{\"testCases\":[],\"clarifications\":[]}");

        service.generate(markdown);

        ArgumentCaptor<String> userText = ArgumentCaptor.forClass(String.class);
        verify(openRouter).complete(anyString(), userText.capture());
        String urlLine = userText.getValue().lines()
                .filter(line -> line.startsWith("- URL: "))
                .findFirst()
                .orElseThrow();
        assertThat(urlLine).contains("/users/{id}").doesNotContain("/or").doesNotContain("/7");
    }

    /**
     * Ссылка FR-UI-1 не совпадает с заголовком FR-UI-10: раздел берётся по целому идентификатору.
     */
    @Test
    void requirementBlock_refMatchesWholeIdentifierOnly() throws Exception {
        String markdown = """
                ## FR-UI-10 Таймаут
                Лимит 600 символов.

                ## FR-UI-1 Вход
                Кнопка Войти.
                """;
        Method extract = SyncGenerationService.class.getDeclaredMethod(
                "extractRequirementBlock", String.class, String.class);
        extract.setAccessible(true);

        String block = (String) extract.invoke(service, markdown, "FR-UI-1");

        assertThat(block).contains("Кнопка Войти").doesNotContain("Лимит 600");
    }

    /**
     * Неизвестный тип уточнения не роняет ответ: уточнение становится MISSING_REQUIREMENT.
     */
    @Test
    void unknownClarificationType_becomesMissingRequirement() {
        modelReturns("""
                {"testCases":[%s],
                 "clarifications":[{"requirementRef":"REQ-1","question":"Какой срок хранения?","reason":"нет","type":"SOMETHING_NEW"}]}
                """.formatted(VALID_CASE_1));

        var result = service.generate(MARKDOWN);

        assertThat(result.clarifications()).singleElement().satisfies(c -> {
            assertThat(c.question()).isEqualTo("Какой срок хранения?");
            assertThat(c.type()).isEqualTo(ClarificationType.MISSING_REQUIREMENT);
        });
    }

    @Test
    void autoGeneratedId_doesNotCollideWithIdsReturnedByModel() {
        modelReturns("""
                {"testCases":[
                  {"id":"","type":"UI","title":"Без id","requirementRef":"REQ-1",
                   "steps":["Открыть страницу входа"],"expectedResult":"Страница открыта","priority":"P2"},
                  %s
                ],"clarifications":[]}
                """.formatted(VALID_CASE_1));

        var result = service.generate(MARKDOWN);

        assertThat(result.testCases())
                .extracting(TestCase::id)
                .containsExactly("TC-UI-002", "TC-UI-001");
    }
}
