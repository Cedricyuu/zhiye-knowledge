package cn.knowledge.persistence;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AnswerMapper {
    @Insert("""
        INSERT INTO kb_answer (id, user_id, question, answer, answer_mode, citations_json, elapsed_ms, feedback, created_at)
        VALUES (#{id}, #{userId}, #{question}, #{answer}, #{answerMode}, #{citationsJson}, #{elapsedMs}, #{feedback}, #{createdAt})
        """)
    void insert(Rows.Answer answer);

    @Select("SELECT * FROM kb_answer WHERE user_id = #{userId} ORDER BY created_at DESC, id LIMIT 100")
    List<Rows.Answer> list(String userId);

    @Select("SELECT * FROM kb_answer WHERE id = #{id} AND user_id = #{userId}")
    Rows.Answer owned(@Param("id") String id, @Param("userId") String userId);

    @Update("UPDATE kb_answer SET feedback = #{feedback} WHERE id = #{id} AND user_id = #{userId}")
    int feedback(@Param("id") String id, @Param("userId") String userId, @Param("feedback") Integer feedback);

    @Delete("DELETE FROM kb_answer WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") String id, @Param("userId") String userId);
}
