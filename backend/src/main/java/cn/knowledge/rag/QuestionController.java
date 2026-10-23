package cn.knowledge.rag;

import java.util.List;
import cn.knowledge.auth.UserIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {
    public record QuestionRequest(
            @NotBlank(message = "请输入问题") @Size(max = 1000, message = "问题最多 1000 个字符") String question,
            @Size(max = 50) List<@NotBlank @Size(max = 36) String> documentIds) {}
    public record FeedbackRequest(@Min(-1) @Max(1) Integer feedback) {}
    private final QuestionService questions;
    public QuestionController(QuestionService questions) { this.questions = questions; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    QuestionService.AnswerView ask(@AuthenticationPrincipal UserIdentity user, @Valid @RequestBody QuestionRequest request) {
        return questions.ask(user.id(), request.question(), request.documentIds());
    }

    @GetMapping
    List<QuestionService.AnswerView> history(@AuthenticationPrincipal UserIdentity user) {
        return questions.history(user.id());
    }

    @PatchMapping("/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void feedback(@AuthenticationPrincipal UserIdentity user, @PathVariable String id,
                  @Valid @RequestBody FeedbackRequest request) {
        questions.feedback(user.id(), id, request.feedback());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal UserIdentity user, @PathVariable String id) {
        questions.delete(user.id(), id);
    }
}
