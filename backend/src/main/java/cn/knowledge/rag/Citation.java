package cn.knowledge.rag;

import java.util.List;

public record Citation(int index, String chunkId, String documentId, String documentName, String fileType,
                       int ordinal, int pageNumber, int startOffset, String content, double score,
                       Double semanticScore, Double keywordScore, List<String> supportingQuotes) {
    public Citation(int index, String chunkId, String documentId, String documentName, String fileType,
                    int ordinal, int pageNumber, int startOffset, String content, double score) {
        this(index, chunkId, documentId, documentName, fileType, ordinal, pageNumber, startOffset, content,
                score, null, null, List.of());
    }

    public Citation withQuotes(List<String> quotes) {
        return new Citation(index, chunkId, documentId, documentName, fileType, ordinal, pageNumber,
                startOffset, content, score, semanticScore, keywordScore, List.copyOf(quotes));
    }
}
