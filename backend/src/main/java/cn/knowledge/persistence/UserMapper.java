package cn.knowledge.persistence;

import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
    @Select("SELECT * FROM kb_user WHERE username = #{username}")
    Rows.User findByUsername(String username);

    @Insert("""
        INSERT INTO kb_user (id, username, display_name, password_hash, created_at)
        VALUES (#{id}, #{username}, #{displayName}, #{passwordHash}, #{createdAt})
        """)
    void insert(Rows.User user);

    @Insert("INSERT INTO kb_session (token_hash, user_id, expires_at) VALUES (#{hash}, #{userId}, #{expiresAt})")
    void addSession(@Param("hash") String hash, @Param("userId") String userId, @Param("expiresAt") long expiresAt);

    @Select("""
        SELECT u.* FROM kb_user u JOIN kb_session s ON u.id = s.user_id
        WHERE s.token_hash = #{hash} AND s.expires_at > #{now}
        """)
    Rows.User findSession(@Param("hash") String hash, @Param("now") long now);

    @Delete("DELETE FROM kb_session WHERE token_hash = #{hash}")
    void deleteSession(String hash);

    @Delete("DELETE FROM kb_session WHERE expires_at <= #{now}")
    void deleteExpired(long now);
}
