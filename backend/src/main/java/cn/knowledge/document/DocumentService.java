package cn.knowledge.document;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import cn.knowledge.auth.TokenCodec;
import cn.knowledge.common.ApiException;
import cn.knowledge.persistence.DocumentMapper;
import cn.knowledge.persistence.EmbeddingMapper;
import cn.knowledge.persistence.Rows;
import cn.knowledge.rag.EmbeddingService;
import cn.knowledge.rag.VectorIndexService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static java.util.stream.Collectors.toMap;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    public record DocumentView(String id, String name, String fileType, long sizeBytes,
                               int pageCount, int chunkCount, int indexedChunks, LocalDateTime createdAt) {}
    public record PartView(String id, int ordinal, int pageNumber, int startOffset, String content) {}
    public record DocumentDetail(DocumentView document, List<PartView> parts) {}

    private final DocumentMapper documents;
    private final DocumentParser parser;
    private final VectorIndexService index;
    private final EmbeddingMapper vectors;
    private final TransactionTemplate writes;
    public DocumentService(DocumentMapper documents, DocumentParser parser, VectorIndexService index, EmbeddingMapper vectors,
                           PlatformTransactionManager transactions) {
        this.documents = documents;
        this.parser = parser;
        this.index = index;
        this.vectors = vectors;
        this.writes = new TransactionTemplate(transactions);
    }

    public List<DocumentView> list(String userId) {
        var rows = documents.list(userId);
        if (rows.isEmpty()) { return List.of(); }
        Map<String, Integer> counts = vectors.indexedCounts(userId, EmbeddingService.MODEL).stream()
                .collect(toMap(EmbeddingMapper.DocumentCount::documentId, EmbeddingMapper.DocumentCount::indexedChunks));
        return rows.stream().map(row -> view(row, counts.getOrDefault(row.id(), 0))).toList();
    }

    public DocumentView upload(String userId, MultipartFile file) {
        try {
            return ingest(userId, file.getOriginalFilename(), file.getBytes());
        } catch (IOException ex) {
            throw ApiException.badRequest("读取上传文件失败，请重新选择文件");
        }
    }

    public DocumentView sample(String userId) {
        try (var input = new ClassPathResource("samples/java-guide.md").getInputStream()) {
            return ingest(userId, "Java 学习手册（示例）.md", input.readAllBytes());
        } catch (IOException ex) {
            throw new IllegalStateException("Sample document is missing", ex);
        }
    }

    public DocumentDetail detail(String userId, String id) {
        var document = documents.owned(id, userId);
        if (document == null) { throw ApiException.notFound(); }
        var parts = documents.chunks(id, userId).stream().map(c ->
                new PartView(c.id(), c.ordinal(), c.pageNumber(), c.startOffset(), c.content())).toList();
        return new DocumentDetail(view(document), parts);
    }

    public DocumentView reindex(String userId, String id) {
        var document = documents.owned(id, userId);
        if (document == null) { throw ApiException.notFound(); }
        var prepared = index.prepare(documents.chunks(id, userId));
        return writes.execute(status -> {
            var current = documents.lockOwned(id, userId);
            if (current == null) { throw ApiException.notFound(); }
            index.savePrepared(prepared);
            return view(current, prepared.size());
        });
    }

    @Transactional
    public void delete(String userId, String id) {
        if (documents.delete(id, userId) == 0) { throw ApiException.notFound(); }
    }

    private DocumentView ingest(String userId, String originalName, byte[] bytes) {
        if (bytes.length == 0) { throw ApiException.badRequest("文件为空，请选择有内容的文档"); }
        if (bytes.length > 10 * 1024 * 1024) { throw ApiException.badRequest("单个文件不能超过 10 MB"); }
        String name = originalName == null ? "document" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.length() > 200 || name.isEmpty()) { throw ApiException.badRequest("文件名须为 1–200 个字符"); }
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!List.of("pdf", "txt", "md").contains(type)) {
            throw ApiException.badRequest("支持 PDF、TXT 和 Markdown 文件");
        }
        String sha256 = TokenCodec.sha256(bytes);
        checkAdmission(userId, sha256);
        var parsed = parser.parse(type, bytes);
        String id = UUID.randomUUID().toString();
        var document = new Rows.Document(id, userId, name, type, bytes.length, sha256,
                parsed.pageCount(), parsed.parts().size(), LocalDateTime.now(ZoneOffset.UTC));
        var chunks = parsed.parts().stream().map(part -> new Rows.Chunk(UUID.randomUUID().toString(), id, userId,
                part.ordinal(), part.pageNumber(), part.startOffset(), part.content())).toList();
        var prepared = index.prepare(chunks);
        return writes.execute(status -> {
            // Recheck after acquiring the user row so concurrent uploads cannot bypass the limit.
            if (documents.lockOwner(userId) == null) { throw ApiException.notFound(); }
            checkAdmission(userId, sha256);
            documents.insert(document);
            for (int start = 0; start < chunks.size(); start += 64) {
                documents.insertChunks(chunks.subList(start, Math.min(start + 64, chunks.size())));
            }
            index.savePrepared(prepared);
            return view(document, prepared.size());
        });
    }

    private void checkAdmission(String userId, String sha256) {
        if (documents.count(userId) >= 50) { throw ApiException.badRequest("第一版每个账号最多保存 50 份文档"); }
        if (documents.duplicate(userId, sha256) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "这份资料已经在你的知识库中");
        }
    }

    private DocumentView view(Rows.Document row) {
        return view(row, vectors.indexedCount(row.id(), row.userId(), EmbeddingService.MODEL));
    }

    private DocumentView view(Rows.Document row, int indexedChunks) {
        return new DocumentView(row.id(), row.name(), row.fileType(), row.sizeBytes(), row.pageCount(),
                row.chunkCount(), indexedChunks, row.createdAt());
    }
}
