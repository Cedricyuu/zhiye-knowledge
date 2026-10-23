package cn.knowledge.rag;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import cn.knowledge.persistence.Rows;
import org.springframework.stereotype.Component;

@Component
public class KeywordRetriever {
    private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_]+");
    private static final Set<String> STOP = Set.of("什么", "怎么", "如何", "可以", "这个", "那个", "我们",
            "哪些", "是否", "请问", "介绍", "根据", "解释", "有关", "以及", "应该", "相关", "多少", "怎样",
            "the", "is", "are", "a", "an", "of", "to", "in", "on", "what", "how", "and", "for", "does", "do");
    public record Hit(Rows.SearchChunk chunk, double score) {}

    public List<Hit> retrieve(String question, List<Rows.SearchChunk> chunks, int limit) {
        var query = new HashSet<>(tokens(question));
        if (query.isEmpty() || chunks.isEmpty()) { return List.of(); }
        List<Map<String, Integer>> frequencies = new ArrayList<>();
        int[] lengths = new int[chunks.size()];
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            // Score source content directly, so a title alone cannot make an unrelated chunk a hit.
            var terms = tokens(chunks.get(i).content());
            lengths[i] = terms.size();
            var frequency = new HashMap<String, Integer>();
            terms.forEach(term -> frequency.merge(term, 1, Integer::sum));
            frequencies.add(frequency);
            query.stream().filter(frequency::containsKey).forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }
        double average = Arrays.stream(lengths).average().orElse(1);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            double score = 0;
            int matched = 0;
            for (String term : query) {
                int tf = frequencies.get(i).getOrDefault(term, 0);
                if (tf == 0) { continue; }
                matched++;
                int df = documentFrequency.get(term);
                double idf = Math.log(1 + (chunks.size() - df + 0.5) / (df + 0.5));
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * lengths[i] / Math.max(1, average)));
            }
            if (matched > 0 && score > 0) { hits.add(new Hit(chunks.get(i), score)); }
        }
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(hit -> hit.chunk().documentId()).thenComparingInt(hit -> hit.chunk().ordinal()))
                .limit(limit).toList();
    }

    List<String> tokens(String input) {
        String text = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String phrase : List.of("是什么", "有什么", "为什么", "请问", "一下")) { text = text.replace(phrase, " "); }
        var matcher = WORDS.matcher(text);
        List<String> terms = new ArrayList<>();
        while (matcher.find()) {
            String word = matcher.group();
            int[] points = word.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
                if (points.length == 1 && !"的了是在与和我你它吗呢有请问".contains(word)) { terms.add(word); }
                for (int i = 0; i + 1 < points.length; i++) {
                    String term = new String(points, i, 2);
                    if (!STOP.contains(term)) { terms.add(term); }
                }
            } else if (!STOP.contains(word)) { terms.add(word); }
        }
        return terms;
    }
}
