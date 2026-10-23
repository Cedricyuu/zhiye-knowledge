package cn.knowledge;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Reuse the business acceptance cases against an explicitly created, disposable project MySQL DB.
@EnabledIfEnvironmentVariable(named = "KB_RUN_MYSQL_TESTS", matches = "true")
@SpringBootTest(properties = "kb.ai-enabled=false")
@ActiveProfiles("mysql")
class MysqlKnowledgeFlowTest extends KnowledgeFlowTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) { MysqlTestDatabase.configure(registry); }

    @Test void preservesFourByteChineseTextAndMicrosecondsOnActualMysql() throws Exception {
        assertThat(db.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName())).isEqualTo("MySQL");
        String name = "utf8_" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        var response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("username", name, "displayName", "中文🧠", "password", "mysql-fixture-123"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.displayName").value("中文🧠"))
                .andReturn().getResponse();
        var account = json.readTree(response.getContentAsByteArray());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + account.path("token").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("中文🧠"));
        var preciseTime = LocalDateTime.of(2026, 10, 17, 0, 0, 0, 123456000);
        db.update("UPDATE kb_user SET created_at=? WHERE id=?", preciseTime, account.path("user").path("id").asText());
        assertThat(db.queryForObject("SELECT created_at FROM kb_user WHERE id=?", LocalDateTime.class,
                account.path("user").path("id").asText())).isEqualTo(preciseTime);
    }
}

final class MysqlTestDatabase {
    private MysqlTestDatabase() {}
    static void configure(DynamicPropertyRegistry registry) {
        String url = System.getenv("KB_MYSQL_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13307/ai_knowledge_verify_[a-f0-9]{12}\\?.+")) {
            throw new IllegalStateException("MySQL acceptance must use a disposable project DB. Run scripts/test-mysql.ps1.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "knowledge");
        registry.add("spring.datasource.password", () -> System.getenv("KB_MYSQL_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Several test contexts coexist with the running app on this 30-connection local instance.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }
}
