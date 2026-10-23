package cn.knowledge.rag;

import java.util.*;
import cn.knowledge.persistence.Rows;
import org.springframework.stereotype.Component;

@Component
public class HybridRetriever {
    public record Hit(Rows.SearchChunk chunk, double score, double semanticScore, double keywordScore) {}
    private final KeywordRetriever keywords;
    private final EmbeddingService embeddings;
    private final VectorIndexService index;

    public HybridRetriever(KeywordRetriever keywords, EmbeddingService embeddings, VectorIndexService index) {
        this.keywords = keywords; this.embeddings = embeddings; this.index = index;
    }

    public List<Hit> retrieve(String userId, String question, List<String> documentIds,
                              List<Rows.SearchChunk> chunks, int limit) {
        if (chunks.isEmpty()) { return List.of(); }
        var vectors = index.loadOrBuild(userId, documentIds, chunks);
        float[] query = embeddings.query(question);
        var sparse = keywords.retrieve(question, chunks, 12);
        var keywordScores = new HashMap<String, Double>();
        sparse.forEach(hit -> keywordScores.put(hit.chunk().id(), hit.score()));
        var semanticOrder = Comparator.comparingDouble(Hit::semanticScore).reversed()
                .thenComparing(hit -> hit.chunk().documentId()).thenComparingInt(hit -> hit.chunk().ordinal());
        var best = new PriorityQueue<Hit>(12, semanticOrder.reversed());
        var candidates = new HashMap<String, Hit>();
        // Keep only the top 12 semantic hits and the 12 keyword hits, instead of sorting every chunk.
        for (var chunk : chunks) {
            var hit = new Hit(chunk, 0, EmbeddingService.cosine(query, vectors.get(chunk.id())),
                    keywordScores.getOrDefault(chunk.id(), 0.0));
            if (keywordScores.containsKey(chunk.id())) { candidates.put(chunk.id(), hit); }
            if (best.size() < 12) { best.add(hit); }
            else if (semanticOrder.compare(hit, best.peek()) < 0) { best.poll(); best.add(hit); }
        }
        var dense = best.stream().sorted(semanticOrder).toList();
        dense.forEach(hit -> candidates.put(hit.chunk().id(), hit));
        var fused = new HashMap<String, Double>();
        // Threshold is an engineering gate, not a probability. It is covered by the fixed retrieval evaluation.
        for (int i = 0; i < dense.size(); i++) {
            var hit = dense.get(i);
            if (hit.semanticScore() >= 0.52) { fused.merge(hit.chunk().id(), 1.0 / (60 + i + 1), Double::sum); }
        }
        for (int i = 0; i < sparse.size(); i++) {
            var hit = sparse.get(i);
            // A generic single keyword should not bypass a low semantic relevance score.
            if (candidates.get(hit.chunk().id()).semanticScore() >= 0.40) {
                fused.merge(hit.chunk().id(), 1.0 / (60 + i + 1), Double::sum);
            }
        }
        return fused.entrySet().stream().map(entry -> {
                    var candidate = candidates.get(entry.getKey());
                    return new Hit(candidate.chunk(), entry.getValue(), candidate.semanticScore(), candidate.keywordScore());
                })
                .sorted(Comparator.comparingDouble(Hit::score).reversed()
                        .thenComparing(Comparator.comparingDouble(Hit::semanticScore).reversed())
                        .thenComparing(hit -> hit.chunk().documentId()).thenComparingInt(hit -> hit.chunk().ordinal()))
                .limit(limit).toList();
    }
}
