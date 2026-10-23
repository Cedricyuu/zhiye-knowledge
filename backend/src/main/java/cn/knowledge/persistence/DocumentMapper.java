package cn.knowledge.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DocumentMapper {
    @Select("SELECT id FROM kb_user WHERE id = #{userId} FOR UPDATE")
    String lockOwner(String userId);

    @Select("SELECT * FROM kb_document WHERE user_id = #{userId} ORDER BY created_at DESC, id LIMIT 100")
    List<Rows.Document> list(String userId);

    @Select("SELECT COUNT(*) FROM kb_document WHERE user_id = #{userId}")
    int count(String userId);

    @Select("SELECT * FROM kb_document WHERE id = #{id} AND user_id = #{userId}")
    Rows.Document owned(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM kb_document WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    Rows.Document lockOwned(@Param("id") String id, @Param("userId") String userId);

    @Select("SELECT * FROM kb_document WHERE user_id = #{userId} AND sha256 = #{sha256}")
    Rows.Document duplicate(@Param("userId") String userId, @Param("sha256") String sha256);

    @Insert("""
        INSERT INTO kb_document (id, user_id, name, file_type, size_bytes, sha256, page_count, chunk_count, created_at)
        VALUES (#{id}, #{userId}, #{name}, #{fileType}, #{sizeBytes}, #{sha256}, #{pageCount}, #{chunkCount}, #{createdAt})
        """)
    void insert(Rows.Document document);

    @Insert("""
        <script>
        INSERT INTO kb_chunk (id, document_id, user_id, ordinal, page_number, start_offset, content) VALUES
        <foreach collection="chunks" item="chunk" separator=",">
          (#{chunk.id}, #{chunk.documentId}, #{chunk.userId}, #{chunk.ordinal}, #{chunk.pageNumber}, #{chunk.startOffset}, #{chunk.content})
        </foreach>
        </script>
        """)
    void insertChunks(@Param("chunks") List<Rows.Chunk> chunks);

    @Select("SELECT * FROM kb_chunk WHERE document_id = #{documentId} AND user_id = #{userId} ORDER BY ordinal")
    List<Rows.Chunk> chunks(@Param("documentId") String documentId, @Param("userId") String userId);

    // Ownership is filtered by SQL before retrieval. Never rank another user's text and filter afterwards.
    @Select("""
        <script>
        SELECT c.id, c.document_id, d.name AS document_name, d.file_type,
               c.ordinal, c.page_number, c.start_offset, c.content
        FROM kb_chunk c JOIN kb_document d ON c.document_id = d.id AND c.user_id = d.user_id
        WHERE c.user_id = #{userId}
        <if test="documentIds != null and !documentIds.isEmpty()">
          AND c.document_id IN
          <foreach collection="documentIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </if>
        ORDER BY c.document_id, c.ordinal
        </script>
        """)
    List<Rows.SearchChunk> searchable(@Param("userId") String userId, @Param("documentIds") List<String> documentIds);

    @Delete("DELETE FROM kb_document WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") String id, @Param("userId") String userId);
}
