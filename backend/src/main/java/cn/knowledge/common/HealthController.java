package cn.knowledge.common;

import java.util.Map;
import java.sql.SQLException;
import javax.sql.DataSource;
import cn.knowledge.rag.ModelGateway;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final ModelGateway model;
    private final Environment environment;
    private final DataSource dataSource;
    public HealthController(ModelGateway model, Environment environment, DataSource dataSource) {
        this.model = model;
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @GetMapping("/api/health")
    ResponseEntity<Map<String, Object>> health() {
        boolean databaseReady;
        try (var connection = dataSource.getConnection()) {
            databaseReady = connection.isValid(2);
        } catch (SQLException unavailable) {
            databaseReady = false;
        }
        return ResponseEntity.status(databaseReady ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", databaseReady ? "UP" : "DOWN", "version", "0.2.0", "aiEnabled", model.enabled(),
                "answerMode", model.enabled() ? "AI_GENERATED" : "LOCAL_RETRIEVAL",
                "database", environment.matchesProfiles("mysql") ? "MySQL" : "H2", "databaseReady", databaseReady,
                "retrieval", "HYBRID", "embeddingModel", "bge-small-zh-v1.5",
                "modelName", model.modelName(), "localModel", model.local()));
    }
}
