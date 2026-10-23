package cn.knowledge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Opt-in acceptance: real local Qwen calls and real BGE inference, with an isolated in-memory business DB.
@EnabledIfEnvironmentVariable(named = "KB_RUN_LOCAL_AI_TESTS", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:local-rag-acceptance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "kb.ai-enabled=true", "kb.ai-base-url=http://127.0.0.1:18081/v1", "kb.ai-model=qwen3-4b-local"
})
@AutoConfigureMockMvc
class LocalRagAcceptanceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void localKey(DynamicPropertyRegistry registry) throws Exception {
        Path project = Path.of(System.getProperty("kb.project-root", ".."));
        String key = Files.readString(project.resolve(".runtime/model-api-key")).strip();
        registry.add("kb.ai-api-key", () -> key);
    }

    @Test void generatesConciseScheduleAnswerWithVerifiableQuotes() throws Exception {
        String token = register();
        upload(token, "青竹安排.md", "青竹项目的分享会在每周三 19:30 开始。参加前需要准备一个接口演示和一个真实问题的排查记录。");
        JsonNode answer = ask(token, "青竹分享会几点开始？需要准备什么？", List.of());
        assertThat(answer.path("answerMode").asText()).isEqualTo("AI_GENERATED");
        assertThat(answer.path("answer").asText()).contains("19:30", "接口");
        verifyQuotes(answer);
    }

    @Test void answersDifferentWordingThroughSemanticRetrieval() throws Exception {
        String token = register();
        upload(token, "售后条款.md", "您可以在支付成功后的七日内办理退款，款项将沿原支付渠道退回。");
        JsonNode answer = ask(token, "我买的东西不想要了，付款后多久还能退钱？钱会退到哪里？", List.of());
        assertThat(answer.path("answerMode").asText()).isEqualTo("AI_GENERATED");
        assertThat(answer.path("answer").asText()).containsAnyOf("七", "7").contains("原");
        verifyQuotes(answer);
    }

    @Test void refusesToInventAnUnstatedBudget() throws Exception {
        String token = register();
        upload(token, "青竹时间.md", "青竹项目的分享会在每周三 19:30 开始，地点是线上会议室。参加者需要提前准备接口演示。");
        JsonNode answer = ask(token, "青竹项目的预算总额是多少钱？", List.of());
        assertThat(answer.path("answerMode").asText()).isEqualTo("NO_EVIDENCE");
        assertThat(answer.path("citations").isEmpty()).isTrue();
        assertThat(answer.path("answer").asText()).containsAnyOf("依据", "资料");
    }

    @Test void selectedDocumentControlsWhichPolicyIsAnswered() throws Exception {
        String token = register();
        upload(token, "甲店退款.md", "甲店支持在支付成功后的七日内退款，钱款原路返回。");
        String selected = upload(token, "乙店退款.md", "乙店支持在支付成功后的三日内退款，钱款原路返回。");
        JsonNode answer = ask(token, "付款后多久还能退钱？", List.of(selected));
        assertThat(answer.path("answerMode").asText()).isEqualTo("AI_GENERATED");
        assertThat(answer.path("answer").asText()).containsAnyOf("三", "3").doesNotContain("七日", "7天");
        for (JsonNode source : answer.path("citations")) { assertThat(source.path("documentId").asText()).isEqualTo(selected); }
        verifyQuotes(answer);
    }

    @Test void distinguishesSimilarPoliciesWithoutAnExplicitDocumentFilter() throws Exception {
        String token = register();
        upload(token, "白鹭店规定.md", "白鹭店的商品在付款后的七日内可以退款，款项原路退回。");
        String expected = upload(token, "青禾店规定.md", "青禾店的商品在付款后的三日内可以退款，款项原路退回。");
        JsonNode answer = ask(token, "青禾店付款后多久还能退款？", List.of());
        assertThat(answer.path("answerMode").asText()).isEqualTo("AI_GENERATED");
        assertThat(answer.path("answer").asText()).containsAnyOf("三", "3").doesNotContain("七", "7");
        assertThat(answer.path("citations").findValuesAsText("documentId")).contains(expected);
        verifyQuotes(answer);
    }

    @Test void combinesFactsFromSeparatedChunks() throws Exception {
        String token = register();
        upload(token, "苍松工作坊安排.md", "苍松工作坊在每周六 14:20 开始。\n"
                + "资料归档需要记录分类编号，按主题分组整理，索引只描述资料存放位置。".repeat(30)
                + "\n苍松工作坊的报名截止时间是每周四 20:00。");
        JsonNode answer = ask(token, "苍松工作坊什么时候开始？报名什么时候截止？", List.of());
        assertThat(answer.path("answerMode").asText()).isEqualTo("AI_GENERATED");
        assertThat(answer.path("answer").asText()).contains("周六", "14:20", "周四", "20:00");
        assertThat(answer.path("citations").findValuesAsText("chunkId").stream().distinct().count()).isGreaterThanOrEqualTo(2);
        verifyQuotes(answer);
    }

    @Test void reportsConflictingSourcesWithoutSilentlyChoosingOne() throws Exception {
        String token = register();
        upload(token, "星河服务说明甲.md", "星河服务的退款期限为付款后七日内。");
        upload(token, "星河服务说明乙.md", "星河服务的退款期限为付款后十四日内。");
        JsonNode answer = ask(token, "两份资料对星河服务退款期限的说法是否一致？分别怎么规定？", List.of());
        assertThat(answer.path("answerMode").asText()).isEqualTo("AI_GENERATED");
        String text = answer.path("answer").asText();
        assertThat(text).containsAnyOf("七", "7").containsAnyOf("十四", "14")
                .containsAnyOf("不一致", "冲突", "不同", "差异");
        assertThat(answer.path("citations").findValuesAsText("documentId").stream().distinct().count()).isEqualTo(2);
        verifyQuotes(answer);
    }

    @Test void preservesNegativeRulesAndStillAnswersTheSupportedSecondPart() throws Exception {
        String token = register();
        upload(token, "晨露培训费用.md", "晨露培训的报名费不予报销。报名成功后可以在订单页申请电子发票。");
        JsonNode answer = ask(token, "晨露培训报名费能报销吗？发票如何申请？", List.of());
        assertThat(answer.path("answerMode").asText()).isEqualTo("AI_GENERATED");
        assertThat(answer.path("answer").asText()).containsAnyOf("不予报销", "不能报销", "不可报销", "不支持报销")
                .contains("订单", "发票");
        verifyQuotes(answer);
    }

    private void verifyQuotes(JsonNode answer) {
        assertThat(answer.path("citations").isEmpty()).isFalse();
        for (JsonNode source : answer.path("citations")) {
            assertThat(source.path("supportingQuotes").isEmpty()).isFalse();
            for (JsonNode quote : source.path("supportingQuotes")) {
                assertThat(source.path("content").asText().replaceAll("\\s+", ""))
                        .contains(quote.asText().replaceAll("\\s+", ""));
            }
        }
    }
    private String register() throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of("username", "live_" + UUID.randomUUID().toString().replace("-", "").substring(0, 15),
                "displayName", "本地模型验收", "password", "acceptance-fixture-123"));
        return json.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray()).path("token").asText();
    }
    private String upload(String token, String name, String text) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", name, "text/markdown",
                text.getBytes(StandardCharsets.UTF_8))).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray()).path("id").asText();
    }
    private JsonNode ask(String token, String question, List<String> documentIds) throws Exception {
        return json.readTree(mvc.perform(post("/api/questions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("question", question, "documentIds", documentIds))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
    }
}
