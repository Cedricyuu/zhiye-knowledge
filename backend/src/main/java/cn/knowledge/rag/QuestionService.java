package cn.knowledge.rag;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import cn.knowledge.common.ApiException;
import cn.knowledge.persistence.AnswerMapper;
import cn.knowledge.persistence.DocumentMapper;
import cn.knowledge.persistence.Rows;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class QuestionService {
    public record AnswerView(String id, String question, String answer, String answerMode,
                             List<Citation> citations, long elapsedMs, Integer feedback, LocalDateTime createdAt) {}
    private final DocumentMapper documents;
    private final AnswerMapper answers;
    private final HybridRetriever retriever;
    private final ModelGateway model;
    private final ObjectMapper json;

    public QuestionService(DocumentMapper documents, AnswerMapper answers, HybridRetriever retriever,
                           ModelGateway model, ObjectMapper json) {
        this.documents = documents;
        this.answers = answers;
        this.retriever = retriever;
        this.model = model;
        this.json = json;
    }

    public AnswerView ask(String userId, String question, List<String> documentIds) {
        long start = System.nanoTime();
        question = question.strip();
        if (question.isEmpty()) { throw ApiException.badRequest("请输入问题"); }
        if (documentIds != null) {
            for (String id : documentIds) {
                if (documents.owned(id, userId) == null) { throw ApiException.notFound(); }
            }
        }
        var chunks = documents.searchable(userId, documentIds);
        var hits = retriever.retrieve(userId, question, documentIds, chunks, 4);
        List<Citation> citations = new ArrayList<>();
        for (var hit : hits) {
            var chunk = hit.chunk();
            citations.add(new Citation(citations.size() + 1, chunk.id(), chunk.documentId(), chunk.documentName(),
                    chunk.fileType(), chunk.ordinal(), chunk.pageNumber(), chunk.startOffset(), chunk.content(),
                    Math.round(hit.score() * 100000) / 100000.0,
                    Math.round(hit.semanticScore() * 1000) / 1000.0,
                    Math.round(hit.keywordScore() * 1000) / 1000.0, List.of()));
        }
        String mode;
        String answer;
        if (citations.isEmpty()) {
            mode = "NO_EVIDENCE";
            answer = "没有在所选资料中找到足够相关的依据。请补充相关资料，或把问题限定在文档涉及的内容内。";
        } else if (model.enabled()) {
            var generated = model.answer(question, citations);
            mode = generated.answerable() ? "AI_GENERATED" : "NO_EVIDENCE";
            answer = generated.text();
            citations = generated.citations();
        } else {
            mode = "LOCAL_RETRIEVAL";
            StringBuilder text = new StringBuilder("在你的资料中找到以下相关原文：\n");
            for (Citation citation : citations) {
                text.append("\n[").append(citation.index()).append("] ").append(citation.documentName())
                        .append(" · 片段 ").append(citation.ordinal()).append("\n").append(citation.content()).append("\n");
            }
            answer = text.toString().strip();
        }
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        try {
            var row = new Rows.Answer(UUID.randomUUID().toString(), userId, question, answer, mode,
                    json.writeValueAsString(citations), elapsed, null, LocalDateTime.now(ZoneOffset.UTC));
            answers.insert(row);
            return view(row);
        } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    public List<AnswerView> history(String userId) {
        return answers.list(userId).stream().map(this::view).toList();
    }

    public void feedback(String userId, String id, Integer feedback) {
        if (answers.feedback(id, userId, feedback) == 0) { throw ApiException.notFound(); }
    }

    public void delete(String userId, String id) {
        if (answers.delete(id, userId) == 0) { throw ApiException.notFound(); }
    }

    private AnswerView view(Rows.Answer row) {
        try {
            return new AnswerView(row.id(), row.question(), row.answer(), row.answerMode(),
                    json.readValue(row.citationsJson(), new TypeReference<>() {}),
                    row.elapsedMs(), row.feedback(), row.createdAt());
        } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
