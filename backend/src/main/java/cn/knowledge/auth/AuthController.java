package cn.knowledge.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record RegisterRequest(
        @NotBlank(message = "请输入用户名")
        @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "用户名须为 3–32 位英文字母、数字或下划线") String username,
        @NotBlank(message = "请输入昵称") @Size(max = 40, message = "昵称最多 40 个字符") String displayName,
        @NotBlank(message = "请输入密码") @Size(min = 8, max = 72, message = "密码须为 8–72 个字符") String password) {}

    public record LoginRequest(
        @NotBlank(message = "请输入用户名") @Size(max = 32) String username,
        @NotBlank(message = "请输入密码") @Size(max = 72) String password) {}

    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.AuthResult register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request.username(), request.displayName(), request.password());
    }

    @PostMapping("/login")
    AuthService.AuthResult login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.username(), request.password());
    }

    @GetMapping("/me")
    UserIdentity me(@AuthenticationPrincipal UserIdentity user) { return user; }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader("Authorization") String authorization) { auth.logout(authorization); }
}
