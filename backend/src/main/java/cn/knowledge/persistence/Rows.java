package cn.knowledge.persistence;

import java.time.LocalDateTime;

public final class Rows {
    private Rows() {}

    public record User(String id, String username, String displayName, String passwordHash,
                       LocalDateTime createdAt) {}
    public record Document(String id, String userId, String name, String fileType, long sizeBytes,
                           String sha256, int pageCount, int chunkCount, LocalDateTime createdAt) {}
    public record Chunk(String id, String documentId, String userId, int ordinal,
                        int pageNumber, int startOffset, String content) {}
    public record SearchChunk(String id, String documentId, String documentName, String fileType,
                              int ordinal, int pageNumber, int startOffset, String content) {}
    public record Answer(String id, String userId, String question, String answer, String answerMode,
                         String citationsJson, long elapsedMs, Integer feedback, LocalDateTime createdAt) {}
}
