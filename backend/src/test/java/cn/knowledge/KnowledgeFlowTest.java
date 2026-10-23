package cn.knowledge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import cn.knowledge.auth.TokenCodec;
import cn.knowledge.persistence.DocumentMapper;
import cn.knowledge.rag.KeywordRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:knowledge-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "kb.ai-enabled=false"
})
@AutoConfigureMockMvc
class KnowledgeFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired KeywordRetriever keywords;
    @Autowired DocumentMapper documents;

    @Test void uploadAskCiteAndReadHistory() throws Exception {
        String token = register();
        String documentId = tree(mvc.perform(post("/api/documents/sample").header("Authorization", bearer(token)))
                .andExpect(status().isCreated())).path("id").asText();
        JsonNode answer = tree(ask(token, "数据库事务的 ACID 是什么？").andExpect(status().isCreated())
                .andExpect(jsonPath("$.answerMode").value("LOCAL_RETRIEVAL")));
        assertThat(answer.path("citations").size()).isGreaterThan(0);
        assertThat(answer.path("citations").toString()).contains("原子性", "一致性", "隔离性", "持久性");
        var source = answer.path("citations").get(0);
        var detail = tree(mvc.perform(get("/api/documents/{id}", documentId).header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
        assertThat(detail.findValuesAsText("content")).contains(source.path("content").asText());
        mvc.perform(get("/api/questions").header("Authorization", bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(answer.path("id").asText()));
    }

    @Test void noEvidenceIsExplicitAndNotGenerated() throws Exception {
        String token = register();
        mvc.perform(post("/api/documents/sample").header("Authorization", bearer(token))).andExpect(status().isCreated());
        ask(token, "海王星轨道半径与火星卫星数量").andExpect(status().isCreated())
                .andExpect(jsonPath("$.answerMode").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    @Test void semanticRetrievalFindsDifferentWordingAmongDistractors() throws Exception {
        String token = register();
        String id = tree(upload(token, "退款规则.md", "您可以在支付成功后的七日内办理退款，款项将沿原支付渠道退回。"))
                .path("id").asText();
        upload(token, "会议安排.md", "青竹分享会在每周三 19:30 开始，参加者需要提前准备接口演示。").andExpect(status().isCreated());
        upload(token, "技术手册.md", "Controller 接收 HTTP 请求，Service 处理业务逻辑，Mapper 执行数据库查询。").andExpect(status().isCreated());
        String userId = tree(mvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))).path("id").asText();
        String question = "我买的东西不想要了，付款后多久还能退钱？";
        assertThat(keywords.retrieve(question, documents.searchable(userId, java.util.List.of(id)), 4)).isEmpty();
        JsonNode answer = tree(ask(token, question).andExpect(status().isCreated()));
        assertThat(answer.path("citations").get(0).path("documentId").asText()).isEqualTo(id);
        assertThat(answer.path("citations").get(0).path("content").asText()).contains("七日");
        assertThat(answer.path("citations").get(0).path("semanticScore").asDouble()).isGreaterThan(0.52);
    }

    @Test void vectorIndexIsPersistentRebuildableAndUserScoped() throws Exception {
        String token = register(); String other = register();
        String id = tree(upload(token, "索引测试.md", "文档保存后会生成向量索引，检索时先按用户隔离，再比较相似度。"))
                .path("id").asText();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_embedding e JOIN kb_chunk c ON c.id=e.chunk_id WHERE c.document_id=?", Integer.class, id)).isEqualTo(1);
        mvc.perform(post("/api/documents/{id}/index", id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        db.update("DELETE FROM kb_embedding WHERE chunk_id IN (SELECT id FROM kb_chunk WHERE document_id=?)", id);
        mvc.perform(get("/api/documents").header("Authorization", bearer(token))).andExpect(jsonPath("$[0].indexedChunks").value(0));
        mvc.perform(post("/api/documents/{id}/index", id).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.indexedChunks").value(1));
        mvc.perform(post("/api/documents/{id}/index", id).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.indexedChunks").value(1));
        mvc.perform(delete("/api/documents/{id}", id).header("Authorization", bearer(token))).andExpect(status().isNoContent());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_embedding e JOIN kb_chunk c ON c.id=e.chunk_id WHERE c.document_id=?", Integer.class, id)).isZero();
    }

    @Test void anotherUserCannotReadRetrieveDeleteOrRateMyContent() throws Exception {
        String owner = register();
        String other = register();
        String id = tree(upload(owner, "private.md", "私密代号海棠。海棠的验证码是 763281。"))
                .path("id").asText();
        String answerId = tree(ask(owner, "海棠验证码")).path("id").asText();
        mvc.perform(get("/api/documents/{id}", id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/documents/{id}", id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents").header("Authorization", bearer(other))).andExpect(jsonPath("$").isEmpty());
        ask(other, "海棠验证码").andExpect(jsonPath("$.answerMode").value("NO_EVIDENCE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("763281"))));
        mvc.perform(post("/api/questions").header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("question", "海棠", "documentIds", new String[]{id}))))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/questions/{id}/feedback", answerId).header("Authorization", bearer(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"feedback\":1}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/questions/{id}", answerId).header("Authorization", bearer(other))).andExpect(status().isNotFound());
    }

    @Test void duplicateIsScopedToUser() throws Exception {
        String first = register();
        String second = register();
        upload(first, "a.txt", "同一份资料内容").andExpect(status().isCreated());
        upload(first, "b.txt", "同一份资料内容").andExpect(status().isConflict());
        upload(second, "a.txt", "同一份资料内容").andExpect(status().isCreated());
    }

    @Test void sessionsAreHashedAndLogoutRevokesThem() throws Exception {
        String token = register();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_session WHERE token_hash = ?", Integer.class, token)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_session WHERE token_hash = ?", Integer.class,
                TokenCodec.sha256(token))).isEqualTo(1);
        String password = db.queryForObject("SELECT u.password_hash FROM kb_user u JOIN kb_session s ON s.user_id=u.id WHERE s.token_hash=?",
                String.class, TokenCodec.sha256(token));
        assertThat(password).startsWith("$2").isNotEqualTo("test-password-123");
        mvc.perform(post("/api/auth/logout").header("Authorization", bearer(token))).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test void expiredSessionIsRejected() throws Exception {
        String token = register();
        db.update("UPDATE kb_session SET expires_at=0 WHERE token_hash=?", TokenCodec.sha256(token));
        mvc.perform(get("/api/documents").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
    }

    @Test void badUploadsDoNotCreateDocuments() throws Exception {
        String token = register();
        upload(token, "payload.html", "<h1>hello</h1>").andExpect(status().isBadRequest());
        upload(token, "empty.txt", "").andExpect(status().isBadRequest());
        upload(token, "broken.pdf", "not a PDF").andExpect(status().isUnprocessableEntity());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{(byte) 0xff}))
                .header("Authorization", bearer(token))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents").header("Authorization", bearer(token))).andExpect(jsonPath("$").isEmpty());
    }

    @Test void pdfCitationsPreserveTheActualPage() throws Exception {
        byte[] bytes;
        try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (String text : new String[]{"First page: Java basics.", "Second page: Zephyr project launches on Wednesday."}) {
                var page = new PDPage(); pdf.addPage(page);
                try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700); stream.showText(text); stream.endText();
                }
            }
            pdf.save(output); bytes = output.toByteArray();
        }
        String token = register();
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "two-pages.pdf", "application/pdf", bytes))
                .header("Authorization", bearer(token))).andExpect(status().isCreated());
        ask(token, "When does Zephyr launch?").andExpect(status().isCreated())
                .andExpect(jsonPath("$.citations[0].pageNumber").value(2))
                .andExpect(jsonPath("$.citations[0].content").value(org.hamcrest.Matchers.containsString("Wednesday")));
    }

    @Test void deletionRemovesSearchableChunksAndKeepsHistorySnapshot() throws Exception {
        String token = register();
        String documentId = tree(upload(token, "snapshot.md", "青竹项目分享会时间：每周三 19:30。" )).path("id").asText();
        String answerId = tree(ask(token, "青竹分享会时间")).path("id").asText();
        mvc.perform(delete("/api/documents/{id}", documentId).header("Authorization", bearer(token))).andExpect(status().isNoContent());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_chunk WHERE document_id=?", Integer.class, documentId)).isZero();
        ask(token, "青竹分享会时间").andExpect(jsonPath("$.answerMode").value("NO_EVIDENCE"));
        var records = tree(mvc.perform(get("/api/questions").header("Authorization", bearer(token))));
        assertThat(records.toString()).contains(answerId, "19:30");
    }

    @Test void feedbackAndHistoryDeletionArePersisted() throws Exception {
        String token = register();
        String id = tree(ask(token, "一个没有资料的问题")).path("id").asText();
        mvc.perform(patch("/api/questions/{id}/feedback", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"feedback\":1}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/questions").header("Authorization", bearer(token))).andExpect(jsonPath("$[0].feedback").value(1));
        mvc.perform(patch("/api/questions/{id}/feedback", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"feedback\":2}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/questions/{id}/feedback", id).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"feedback\":null}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/questions").header("Authorization", bearer(token))).andExpect(jsonPath("$[0].feedback").doesNotExist());
        mvc.perform(delete("/api/questions/{id}", id).header("Authorization", bearer(token))).andExpect(status().isNoContent());
        mvc.perform(get("/api/questions").header("Authorization", bearer(token))).andExpect(jsonPath("$").isEmpty());
    }

    @Test void validationAndUnauthenticatedAccess() throws Exception {
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.aiEnabled").value(false))
                .andExpect(jsonPath("$.databaseReady").value(true));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"displayName\":\"测试\",\"password\":\"short\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"unknown-account\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
        String token = register();
        ask(token, "   ").andExpect(status().isBadRequest());
        ask(token, "字".repeat(1001)).andExpect(status().isBadRequest());
    }

    @Test void fixedRetrievalEvaluation() throws Exception {
        String token = register();
        mvc.perform(post("/api/documents/sample").header("Authorization", bearer(token))).andExpect(status().isCreated());
        try (var input = getClass().getResourceAsStream("/retrieval-evaluation.json")) {
            for (JsonNode item : json.readTree(input)) {
                JsonNode answer = tree(ask(token, item.path("question").asText()).andExpect(status().isCreated()));
                if (item.path("noEvidence").asBoolean()) {
                    assertThat(answer.path("answerMode").asText()).isEqualTo("NO_EVIDENCE");
                } else {
                    assertThat(answer.path("citations").toString()).as(item.path("question").asText())
                            .contains(item.path("expectedSourceContains").asText());
                }
            }
        }
    }

    private String register() throws Exception {
        String username = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return tree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", username, "displayName", "测试账号", "password", "test-password-123"))))
                .andExpect(status().isCreated())).path("token").asText();
    }
    private ResultActions upload(String token, String name, String content) throws Exception {
        return mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8))).header("Authorization", bearer(token)));
    }
    private ResultActions ask(String token, String question) throws Exception {
        return mvc.perform(post("/api/questions").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("question", question))));
    }
    private JsonNode tree(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }
    private String bearer(String token) { return "Bearer " + token; }
}
