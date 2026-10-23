package cn.knowledge.rag;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import cn.knowledge.common.ApiException;
import cn.knowledge.config.KnowledgeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

// Protocol tests use a local HTTP fixture. They do not demonstrate availability or quality of a real model.
class ModelGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Citation> citations = List.of(new Citation(1, "chunk", "document", "测试文档.md", "md",
            1, 1, 0, "青竹分享会在每周三 19:30 开始。", 1.0));

    @Test void langchain4jSendsSourcesAndSystemInstructionToConfiguredEndpoint() throws Exception {
        var captured = new AtomicReference<String>();
        HttpServer server = fixture(200, response(claim("分享会在每周三 19:30 开始。", 1, "青竹分享会在每周三 19:30 开始。")), captured);
        try {
            var answer = gateway(server).answer("分享会几点开始？", citations);
            assertThat(answer.text()).contains("19:30", "[1]");
            assertThat(answer.citations().get(0).supportingQuotes()).containsExactly("青竹分享会在每周三 19:30 开始。");
            var request = json.readTree(captured.get());
            assertThat(request.path("model").asText()).isEqualTo("fixture-model");
            assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(request.path("messages").get(1).path("content").asText()).contains("青竹分享会", "分享会几点开始");
            assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        } finally { server.stop(0); }
    }

    @Test void upstreamFailureDoesNotMasqueradeAsSuccessfulLocalRetrieval() throws Exception {
        HttpServer server = fixture(503, "{\"error\":\"private-provider-debug-info\"}", new AtomicReference<>());
        try {
            assertThatThrownBy(() -> gateway(server).answer("时间", citations)).isInstanceOf(ApiException.class)
                    .hasMessageContaining("模型调用失败").hasMessageNotContaining("private-provider");
        } finally { server.stop(0); }
    }

    @Test void fabricatedCitationNumberIsRejected() throws Exception {
        HttpServer server = fixture(200, response(claim("时间为周五。", 99, "青竹分享会在每周三 19:30 开始。")), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> gateway(server).answer("时间", citations)).isInstanceOf(ApiException.class)
                    .hasMessageContaining("可核对的引用");
        } finally { server.stop(0); }
    }

    @Test void fabricatedQuoteIsRejectedEvenWhenTheCitationNumberExists() throws Exception {
        HttpServer server = fixture(200, response(claim("分享会在周五。", 1, "青竹分享会在每周五 08:30 开始。")), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> gateway(server).answer("时间", citations)).isInstanceOf(ApiException.class)
                    .hasMessageContaining("可核对的引用");
        } finally { server.stop(0); }
    }

    @Test void lackOfEvidenceIsAnExplicitOutcome() throws Exception {
        HttpServer server = fixture(200, response("{\"answerable\":false,\"claims\":[]}"), new AtomicReference<>());
        try {
            var answer = gateway(server).answer("分享会预算是多少？", citations);
            assertThat(answer.answerable()).isFalse();
            assertThat(answer.citations()).isEmpty();
            assertThat(answer.text()).contains("没有足够依据");
        } finally { server.stop(0); }
    }

    @Test void unstructuredOrTruncatedTextIsRejected() throws Exception {
        HttpServer server = fixture(200, response("我认为时间在周五 [1]"), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> gateway(server).answer("时间", citations)).isInstanceOf(ApiException.class)
                    .hasMessageContaining("可核对的引用");
        } finally { server.stop(0); }
    }

    @Test void shortSourceCanBeQuotedInFull() throws Exception {
        var shortSources = List.of(new Citation(1, "c", "d", "安排.md", "md", 1, 1, 0, "每周三开会", 1));
        HttpServer server = fixture(200, response(claim("会议在每周三举行。", 1, "每周三开会")), new AtomicReference<>());
        try {
            assertThat(gateway(server).answer("哪天开会？", shortSources).answerable()).isTrue();
        } finally { server.stop(0); }
    }

    @Test void whitespaceCannotMakeAnUninformativeQuotePassTheLengthGate() throws Exception {
        HttpServer server = fixture(200, response(claim("时间是周五。", 1, "青        竹")), new AtomicReference<>());
        try {
            assertThatThrownBy(() -> gateway(server).answer("时间", citations)).isInstanceOf(ApiException.class)
                    .hasMessageContaining("可核对的引用");
        } finally { server.stop(0); }
    }

    @Test void structuredReviewPayloadIsStableAcrossMapInsertionOrders() throws Exception {
        var captured = new AtomicReference<String>();
        HttpServer server=fixture(200,response("{\"ok\":true}"),captured);
        try {
            var gateway=gateway(server);
            var one=new java.util.LinkedHashMap<String,Object>(); one.put("question","题目"); one.put("criteria",List.of("要点"));
            var two=new java.util.LinkedHashMap<String,Object>(); two.put("criteria",List.of("要点")); two.put("question","题目");
            gateway.structured("检查候选内容",one);
            String first=json.readTree(captured.get()).path("messages").get(1).path("content").asText();
            gateway.structured("检查候选内容",two);
            assertThat(json.readTree(captured.get()).path("messages").get(1).path("content").asText()).isEqualTo(first);
        } finally {server.stop(0);}
    }

    private String claim(String text, int source, String quote) throws Exception {
        return json.writeValueAsString(java.util.Map.of("answerable", true, "claims",
                List.of(java.util.Map.of("text", text, "source", source, "quote", quote))));
    }

    private ModelGateway gateway(HttpServer server) {
        return new ModelGateway(new KnowledgeProperties(24, true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-key", "fixture-model"), json);
    }
    private String response(String content) throws Exception {
        return json.writeValueAsString(java.util.Map.of("id", "fixture", "object", "chat.completion", "created", 1,
                "model", "fixture-model", "choices", List.of(java.util.Map.of("index", 0, "finish_reason", "stop",
                "message", java.util.Map.of("role", "assistant", "content", content))),
                "usage", java.util.Map.of("prompt_tokens", 20, "completion_tokens", 10, "total_tokens", 30)));
    }
    private HttpServer fixture(int status, String body, AtomicReference<String> captured) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start(); return server;
    }
}
