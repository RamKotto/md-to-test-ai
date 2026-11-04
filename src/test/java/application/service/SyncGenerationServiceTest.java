package application.service;

import application.config.AppProperties;
import application.dto.Dtos.ClarificationType;
import application.dto.Dtos.TestCase;
import application.exception.GenerationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты разбора и санитизации ответа модели {@link SyncGenerationService}.
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
}
