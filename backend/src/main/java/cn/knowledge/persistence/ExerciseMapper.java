package cn.knowledge.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ExerciseMapper {
    record Exercise(String id, String userId, String anchorChunkId, String question, String solutionJson,
                    String studentAnswer, String gradeJson, LocalDateTime createdAt, LocalDateTime gradedAt) {}
    @Insert("""
        INSERT INTO kb_exercise (id,user_id,anchor_chunk_id,question,solution_json,created_at)
        VALUES (#{id},#{userId},#{anchorChunkId},#{question},#{solutionJson},#{createdAt})
        """)
    void insert(Exercise row);
    @Select("SELECT * FROM kb_exercise WHERE id=#{id} AND user_id=#{userId}")
    Exercise owned(@Param("id") String id, @Param("userId") String userId);
    @Select("SELECT * FROM kb_exercise WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    Exercise lockOwned(@Param("id") String id, @Param("userId") String userId);
    @Select("SELECT * FROM kb_exercise WHERE user_id=#{userId} ORDER BY created_at DESC,id LIMIT 100")
    List<Exercise> list(String userId);
    @Select("SELECT * FROM kb_exercise WHERE user_id=#{userId} AND grade_json IS NULL ORDER BY created_at DESC,id")
    List<Exercise> pending(String userId);
    @Update("""
        UPDATE kb_exercise SET student_answer=#{answer},grade_json=#{grade},graded_at=#{at}
        WHERE id=#{id} AND user_id=#{userId} AND grade_json IS NULL
        """)
    int grade(@Param("id") String id, @Param("userId") String userId, @Param("answer") String answer,
              @Param("grade") String grade, @Param("at") LocalDateTime at);
    @Update("UPDATE kb_exercise SET student_answer=#{answer} WHERE id=#{id} AND user_id=#{userId} AND grade_json IS NULL")
    int saveDraft(@Param("id") String id, @Param("userId") String userId, @Param("answer") String answer);
    @Delete("DELETE FROM kb_exercise WHERE id=#{id} AND user_id=#{userId}")
    int delete(@Param("id") String id, @Param("userId") String userId);
}
