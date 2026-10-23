package cn.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import cn.knowledge.auth.AuthService;
import cn.knowledge.common.ApiException;
import cn.knowledge.document.DocumentService;
import cn.knowledge.persistence.EmbeddingMapper;
import cn.knowledge.rag.EmbeddingService;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Real database transactions; embedding is a small fixture so transaction/concurrency failures are deterministic.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:document-write-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "kb.ai-enabled=false"
})
class DocumentWriteTest {
    @Autowired DocumentService documents;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;
    @Autowired SqlSessionTemplate sqlSessions;
    @MockitoBean EmbeddingService embeddings;
    @MockitoSpyBean EmbeddingMapper vectors;
    private final AtomicInteger calculations = new AtomicInteger();

    @BeforeEach void embeddingFixture() {
        when(embeddings.documents(anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calculations.incrementAndGet();
            List<String> texts = call.getArgument(0);
            return texts.stream().map(text -> new float[]{1, 0}).toList();
        });
        when(embeddings.encode(any(float[].class))).thenReturn("[1.0,0.0]");
    }

    @Test void uploadAndReindexCalculateOutsideTransactionsAndSaveAllBatches() {
        String user = user();
        var doc = documents.upload(user, file("large.md", "资料归档规则。".repeat(4000)));
        assertThat(doc.chunkCount()).isGreaterThan(64);
        assertThat(doc.indexedChunks()).isEqualTo(doc.chunkCount());
        assertThat(count("kb_chunk", user)).isEqualTo(doc.chunkCount());
        assertThat(documents.reindex(user, doc.id()).indexedChunks()).isEqualTo(doc.chunkCount());
        assertThat(calculations.get()).isGreaterThan(2);
    }

    @Test void aVectorWriteFailureRollsBackTheDocumentChunksAndEarlierVectorBatch() {
        String user = user();
        var writes = new AtomicInteger();
        var realMapper = sqlSessions.getMapper(EmbeddingMapper.class);
        doAnswer(call -> {
            if (writes.incrementAndGet() == 2) { throw new IllegalStateException("fixture second batch failure"); }
            realMapper.saveBatch(call.getArgument(0));
            return null;
        }).when(vectors).saveBatch(anyList());
        assertThatThrownBy(() -> documents.upload(user, file("rollback.md", "事务回滚示例。".repeat(4000))))
                .hasMessageContaining("fixture second batch failure");
        assertThat(count("kb_document", user)).isZero();
        assertThat(count("kb_chunk", user)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM kb_embedding e LEFT JOIN kb_chunk c ON c.id=e.chunk_id WHERE c.id IS NULL", Integer.class)).isZero();
    }

    @Test void concurrentUploadsCannotExceedTheDocumentLimit() throws Exception {
        String user = user();
        for (int i = 0; i < 49; i++) {
            db.update("INSERT INTO kb_document(id,user_id,name,file_type,size_bytes,sha256,page_count,chunk_count,created_at) VALUES(?,?,?,'md',1,?,1,0,CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), user, "fixture-"+i, "fixture-"+i);
        }
        var reachedCalculation = new CountDownLatch(2);
        // documents() is synchronized; meet at encode() after the inference monitor has been released.
        when(embeddings.encode(any(float[].class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reachedCalculation.countDown();
            if (!reachedCalculation.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Concurrent fixture did not arrive"); }
            return "[1.0,0.0]";
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first = () -> uploadAtLimit(user, "first");
            Callable<Boolean> second = () -> uploadAtLimit(user, "second");
            var left = executor.submit(first);
            var right = executor.submit(second);
            assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(count("kb_document", user)).isEqualTo(50);
        } finally { executor.shutdownNow(); }
    }

    @Test void documentListingUsesOneGroupedIndexCountAndKeepsUserIsolation() {
        String user = user();
        var first = documents.upload(user, file("first.md", "第一份资料"));
        var second = documents.upload(user, file("second.md", "第二份资料"));
        documents.upload(user(), file("private.md", "另一个用户的资料"));
        db.update("DELETE FROM kb_embedding WHERE chunk_id IN (SELECT id FROM kb_chunk WHERE document_id=?)", second.id());
        clearInvocations(vectors);
        var listed = documents.list(user);
        assertThat(listed).hasSize(2);
        assertThat(listed.stream().filter(doc -> doc.id().equals(first.id())).findFirst().orElseThrow().indexedChunks()).isEqualTo(1);
        assertThat(listed.stream().filter(doc -> doc.id().equals(second.id())).findFirst().orElseThrow().indexedChunks()).isZero();
        verify(vectors, times(1)).indexedCounts(user, EmbeddingService.MODEL);
        verify(vectors, never()).indexedCount(anyString(), anyString(), anyString());
    }

    private boolean uploadAtLimit(String user, String text) {
        try { documents.upload(user, file(text+".md", text)); return true; }
        catch (ApiException limit) {
            assertThat(limit.status().value()).isEqualTo(400);
            assertThat(limit.getMessage()).contains("50");
            return false;
        }
    }
    private String user() {
        return auth.register("write_"+UUID.randomUUID().toString().replace("-", "").substring(0, 16), "事务验收", "fixture-password-123").user().id();
    }
    private int count(String table, String user) {
        return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?", Integer.class, user);
    }
    private MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("file", name, "text/markdown", text.getBytes(StandardCharsets.UTF_8));
    }
}
