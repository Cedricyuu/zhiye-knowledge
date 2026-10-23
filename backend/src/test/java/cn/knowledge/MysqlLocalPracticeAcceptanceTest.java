package cn.knowledge;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="KB_RUN_MYSQL_TESTS",matches="true")
@ActiveProfiles("mysql")
class MysqlLocalPracticeAcceptanceTest extends LocalPracticeAcceptanceTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) { MysqlTestDatabase.configure(registry); }
}
