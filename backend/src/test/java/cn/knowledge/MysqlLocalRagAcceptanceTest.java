package cn.knowledge;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "KB_RUN_MYSQL_TESTS", matches = "true")
@SpringBootTest(properties = {
        "kb.ai-enabled=true", "kb.ai-base-url=http://127.0.0.1:18081/v1", "kb.ai-model=qwen3-4b-local"
})
@ActiveProfiles("mysql")
class MysqlLocalRagAcceptanceTest extends LocalRagAcceptanceTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) { MysqlTestDatabase.configure(registry); }
}
