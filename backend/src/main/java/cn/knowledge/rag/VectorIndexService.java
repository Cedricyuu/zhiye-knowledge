package cn.knowledge.rag;

import java.util.*;
import cn.knowledge.persistence.EmbeddingMapper;
import cn.knowledge.persistence.Rows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class VectorIndexService {
    private final EmbeddingService embeddings;
    private final EmbeddingMapper vectors;
    public VectorIndexService(EmbeddingService embeddings, EmbeddingMapper vectors) {
        this.embeddings = embeddings; this.vectors = vectors;
    }

    public List<EmbeddingMapper.VectorRow> prepare(List<Rows.Chunk> chunks) {
        List<EmbeddingMapper.VectorRow> prepared = new ArrayList<>(chunks.size());
        // Small batches bound the native inference memory even for a large uploaded document.
        for (int start = 0; start < chunks.size(); start += 8) {
            var batch = chunks.subList(start, Math.min(start + 8, chunks.size()));
            var embedded = embeddings.documents(batch.stream().map(Rows.Chunk::content).toList());
            for (int i = 0; i < batch.size(); i++) {
                prepared.add(new EmbeddingMapper.VectorRow(batch.get(i).id(), EmbeddingService.MODEL,
                        embeddings.encode(embedded.get(i))));
            }
        }
        return prepared;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void savePrepared(List<EmbeddingMapper.VectorRow> prepared) {
        for (int start = 0; start < prepared.size(); start += 64) {
            vectors.saveBatch(prepared.subList(start, Math.min(start + 64, prepared.size())));
        }
    }

    public Map<String, float[]> loadOrBuild(String userId, List<String> documentIds, List<Rows.SearchChunk> chunks) {
        Map<String, float[]> result = new HashMap<>();
        for (var row : vectors.owned(userId, documentIds)) {
            if (EmbeddingService.MODEL.equals(row.modelVersion())) { result.put(row.chunkId(), embeddings.decode(row.vectorJson())); }
        }
        var missing = chunks.stream().filter(chunk -> !result.containsKey(chunk.id())).toList();
        for (int start = 0; start < missing.size(); start += 8) {
            var batch = missing.subList(start, Math.min(start + 8, missing.size()));
            var embedded = embeddings.documents(batch.stream().map(Rows.SearchChunk::content).toList());
            for (int i = 0; i < batch.size(); i++) {
                var row = new EmbeddingMapper.VectorRow(batch.get(i).id(), EmbeddingService.MODEL, embeddings.encode(embedded.get(i)));
                vectors.saveOwned(userId, row); result.put(row.chunkId(), embedded.get(i));
            }
        }
        return result;
    }
}
