package cn.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kb")
public record KnowledgeProperties(int sessionHours, boolean aiEnabled,
                                  String aiBaseUrl, String aiApiKey, String aiModel) {}
