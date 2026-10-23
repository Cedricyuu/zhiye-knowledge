package cn.knowledge.rag;

import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingService {
    public static final String MODEL = "bge-small-zh-v15-q:1.22.0-beta32";
    private final EmbeddingModel model = new BgeSmallZhV15QuantizedEmbeddingModel(Runnable::run);
    private final ObjectMapper json;

    public EmbeddingService(ObjectMapper json) { this.json = json; }

    // Serialize inference on a small local machine. Batches cannot create an unbounded thread pool.
    public synchronized List<float[]> documents(List<String> texts) {
        return model.embedAll(texts.stream().map(TextSegment::from).toList()).content().stream()
                .map(embedding -> embedding.vector()).toList();
    }

    public synchronized float[] query(String text) {
        return model.embed("为这个句子生成表示以用于检索相关文章：" + text).content().vector();
    }

    public String encode(float[] vector) {
        try { return json.writeValueAsString(vector); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    public float[] decode(String vector) {
        try { return json.readValue(vector, float[].class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid stored vector", ex); }
    }

    public static double cosine(float[] left, float[] right) {
        if (left.length != right.length) { throw new IllegalArgumentException("Embedding dimensions differ"); }
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i]; leftNorm += left[i] * left[i]; rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }
}
