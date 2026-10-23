package cn.knowledge.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EmbeddingMapper {
    record VectorRow(String chunkId, String modelVersion, String vectorJson) {}
    record DocumentCount(String documentId, int indexedChunks) {}

    @Insert("""
        <script>
        INSERT INTO kb_embedding (chunk_id, model_version, vector_json) VALUES
        <foreach collection="rows" item="row" separator=",">
          (#{row.chunkId}, #{row.modelVersion}, #{row.vectorJson})
        </foreach>
        ON DUPLICATE KEY UPDATE model_version = VALUES(model_version), vector_json = VALUES(vector_json)
        </script>
        """)
    void saveBatch(@Param("rows") List<VectorRow> rows);

    // Backfill only a chunk that still belongs to this user; a concurrent deletion must not resurrect it.
    @Insert("""
        INSERT INTO kb_embedding (chunk_id, model_version, vector_json)
        SELECT id, #{row.modelVersion}, #{row.vectorJson} FROM kb_chunk
        WHERE id = #{row.chunkId} AND user_id = #{userId}
        ON DUPLICATE KEY UPDATE model_version = VALUES(model_version), vector_json = VALUES(vector_json)
        """)
    void saveOwned(@Param("userId") String userId, @Param("row") VectorRow row);

    // Even vector reads join the owned chunks. A global vector search is never used for a user's request.
    @Select("""
        <script>
        SELECT e.chunk_id, e.model_version, e.vector_json FROM kb_embedding e
        JOIN kb_chunk c ON c.id = e.chunk_id
        WHERE c.user_id = #{userId}
        <if test="documentIds != null and !documentIds.isEmpty()">
          AND c.document_id IN
          <foreach collection="documentIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        </script>
        """)
    List<VectorRow> owned(@Param("userId") String userId, @Param("documentIds") List<String> documentIds);

    @Select("SELECT COUNT(*) FROM kb_embedding e JOIN kb_chunk c ON c.id=e.chunk_id WHERE c.document_id=#{documentId} AND c.user_id=#{userId} AND e.model_version=#{model}")
    int indexedCount(@Param("documentId") String documentId, @Param("userId") String userId, @Param("model") String model);

    @Select("""
        SELECT c.document_id, COUNT(*) AS indexed_chunks FROM kb_embedding e
        JOIN kb_chunk c ON c.id = e.chunk_id
        WHERE c.user_id = #{userId} AND e.model_version = #{model}
        GROUP BY c.document_id
        """)
    List<DocumentCount> indexedCounts(@Param("userId") String userId, @Param("model") String model);
}
