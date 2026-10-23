package cn.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import cn.knowledge.config.KnowledgeProperties;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
