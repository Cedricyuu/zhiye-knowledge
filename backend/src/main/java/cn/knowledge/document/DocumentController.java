package cn.knowledge.document;

import java.util.List;
import cn.knowledge.auth.UserIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documents;
    public DocumentController(DocumentService documents) { this.documents = documents; }

    @GetMapping
    List<DocumentService.DocumentView> list(@AuthenticationPrincipal UserIdentity user) {
        return documents.list(user.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DocumentService.DocumentView upload(@AuthenticationPrincipal UserIdentity user,
                                        @RequestParam("file") MultipartFile file) {
        return documents.upload(user.id(), file);
    }

    @PostMapping("/sample")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentService.DocumentView sample(@AuthenticationPrincipal UserIdentity user) {
        return documents.sample(user.id());
    }

    @GetMapping("/{id}")
    DocumentService.DocumentDetail detail(@AuthenticationPrincipal UserIdentity user, @PathVariable String id) {
        return documents.detail(user.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal UserIdentity user, @PathVariable String id) {
        documents.delete(user.id(), id);
    }

    @PostMapping("/{id}/index")
    DocumentService.DocumentView reindex(@AuthenticationPrincipal UserIdentity user, @PathVariable String id) {
        return documents.reindex(user.id(), id);
    }
}
