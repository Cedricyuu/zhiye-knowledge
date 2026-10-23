package cn.knowledge.rag;

import java.util.*;
import cn.knowledge.persistence.Rows;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridRetrieverTest {
    @Test void fusionKeepsKeywordCandidatesOutsideTheSemanticTopTwelve() {
        List<Rows.SearchChunk> chunks = new ArrayList<>();
        Map<String, float[]> vectors = new HashMap<>();
        for (int i = 0; i < 14; i++) {
            chunks.add(chunk("c"+i, i)); vectors.put("c"+i, vector(0.99-i*0.01));
        }
        var keyword = chunk("keyword", 15);
        chunks.add(keyword); vectors.put(keyword.id(), vector(0.50));
        var hits = retrieve(chunks, vectors, List.of(new KeywordRetriever.Hit(keyword, 5), new KeywordRetriever.Hit(chunks.get(3), 4)), 4);
        assertThat(hits.stream().map(hit -> hit.chunk().id())).containsExactly("c3", "c0", "keyword", "c1");
    }

    @Test void equalSemanticScoresHaveStableSourceOrderingAtTheTopTwelveBoundary() {
        List<Rows.SearchChunk> chunks = new ArrayList<>();
        Map<String, float[]> vectors = new HashMap<>();
        for (int i = 0; i < 30; i++) { chunks.add(chunk("c"+i, i)); vectors.put("c"+i, vector(0.8)); }
        Collections.shuffle(chunks, new Random(37));
        assertThat(retrieve(chunks, vectors, List.of(), 4).stream().map(hit -> hit.chunk().id()))
                .containsExactly("c0", "c1", "c2", "c3");
    }

    @Test void aKeywordHitBelowTheSemanticFloorStillProducesNoEvidence() {
        var chunk = chunk("weak", 1);
        assertThat(retrieve(List.of(chunk), Map.of("weak", vector(0.39)), List.of(new KeywordRetriever.Hit(chunk, 100)), 4)).isEmpty();
    }

    private List<HybridRetriever.Hit> retrieve(List<Rows.SearchChunk> chunks, Map<String, float[]> vectors,
                                              List<KeywordRetriever.Hit> sparse, int limit) {
        var keywords = mock(KeywordRetriever.class);
        var embeddings = mock(EmbeddingService.class);
        var index = mock(VectorIndexService.class);
        when(keywords.retrieve(anyString(), anyList(), eq(12))).thenReturn(sparse);
        when(embeddings.query(anyString())).thenReturn(new float[]{1, 0});
        when(index.loadOrBuild(eq("owner"), anyList(), anyList())).thenReturn(vectors);
        return new HybridRetriever(keywords, embeddings, index).retrieve("owner", "question", List.of("document"), chunks, limit);
    }
    private Rows.SearchChunk chunk(String id, int ordinal) {
        return new Rows.SearchChunk(id, "document", "fixture.md", "md", ordinal, 1, 0, "retrieval fixture");
    }
    private float[] vector(double cosine) { return new float[]{(float)cosine, (float)Math.sqrt(1-cosine*cosine)}; }
}
