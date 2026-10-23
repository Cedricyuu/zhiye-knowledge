package cn.knowledge.practice;

import java.util.List;
import cn.knowledge.auth.UserIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/practice")
public class PracticeController {
    public record CreateRequest(@Size(max=50) List<@NotBlank @Size(max=36) String> documentIds) {}
    public record AnswerRequest(@NotBlank(message="请先写下你的回答") @Size(max=4000,message="回答最多4000个字符") String answer,
                                @Size(max=4000,message="原草稿最多4000个字符") String expectedAnswer) {}
    public record DraftRequest(@NotNull(message="草稿内容不能为空值") @Size(max=4000,message="回答最多4000个字符") String answer,
                               @Size(max=4000,message="原草稿最多4000个字符") String expectedAnswer) {}
    private final PracticeService practice;
    public PracticeController(PracticeService practice) { this.practice = practice; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PracticeService.ExerciseView create(@AuthenticationPrincipal UserIdentity user, @Valid @RequestBody CreateRequest request) {
        return practice.create(user.id(), request.documentIds());
    }
    @GetMapping
    List<PracticeService.ExerciseView> list(@AuthenticationPrincipal UserIdentity user) { return practice.list(user.id()); }
    @GetMapping("/{id}")
    PracticeService.ExerciseView get(@AuthenticationPrincipal UserIdentity user, @PathVariable String id) { return practice.get(user.id(), id); }
    @PostMapping("/{id}/answer")
    PracticeService.ExerciseView answer(@AuthenticationPrincipal UserIdentity user, @PathVariable String id, @Valid @RequestBody AnswerRequest request) {
        return practice.grade(user.id(), id, request.answer(), request.expectedAnswer());
    }
    @PatchMapping("/{id}/draft")
    PracticeService.ExerciseView draft(@AuthenticationPrincipal UserIdentity user, @PathVariable String id, @Valid @RequestBody DraftRequest request) {
        return practice.saveDraft(user.id(), id, request.answer(), request.expectedAnswer());
    }
    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    PracticeService.ExerciseView retry(@AuthenticationPrincipal UserIdentity user, @PathVariable String id) {
        return practice.retry(user.id(), id);
    }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal UserIdentity user, @PathVariable String id) { practice.delete(user.id(), id); }
}
