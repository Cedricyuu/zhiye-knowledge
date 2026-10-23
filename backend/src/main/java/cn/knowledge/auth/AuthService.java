package cn.knowledge.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import cn.knowledge.common.ApiException;
import cn.knowledge.config.KnowledgeProperties;
import cn.knowledge.persistence.Rows;
import cn.knowledge.persistence.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    public record AuthResult(String token, UserIdentity user) {}
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final KnowledgeProperties properties;
    private final String dummyHash;

    public AuthService(UserMapper users, PasswordEncoder passwords, KnowledgeProperties properties) {
        this.users = users;
        this.passwords = passwords;
        this.properties = properties;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResult register(String username, String displayName, String password) {
        username = username.toLowerCase(Locale.ROOT);
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("密码的 UTF-8 长度不能超过 72 字节");
        }
        if (displayName.isBlank()) { throw ApiException.badRequest("请输入昵称"); }
        if (users.findByUsername(username) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "这个用户名已被注册");
        }
        var user = new Rows.User(UUID.randomUUID().toString(), username, displayName.strip(),
                passwords.encode(password), LocalDateTime.now(ZoneOffset.UTC));
        users.insert(user);
        return newSession(user);
    }

    @Transactional
    public AuthResult login(String username, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码不正确");
        }
        var user = users.findByUsername(username.toLowerCase(Locale.ROOT));
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.passwordHash());
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码不正确");
        }
        return newSession(user);
    }

    public void logout(String authorization) {
        users.deleteSession(TokenCodec.sha256(authorization.substring(7)));
    }

    private AuthResult newSession(Rows.User user) {
        long now = Instant.now().getEpochSecond();
        users.deleteExpired(now);
        String token = TokenCodec.newToken();
        users.addSession(TokenCodec.sha256(token), user.id(), now + properties.sessionHours() * 3600L);
        return new AuthResult(token, new UserIdentity(user.id(), user.username(), user.displayName()));
    }
}
