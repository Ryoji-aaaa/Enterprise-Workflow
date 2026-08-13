package jp.co.sdcj.workflow.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.ExpenseApplicationAttachmentService;
import jp.co.sdcj.workflow.service.OpenedExpenseAttachment;

@RestController
@Profile("!manual-seed")
@RequestMapping("/api/expense-applications/{applicationId}/attachments")
public class ExpenseApplicationAttachmentController {

    private final ExpenseApplicationAttachmentService attachmentService;
    private final CurrentUserProvider currentUserProvider;

    public ExpenseApplicationAttachmentController(
            ExpenseApplicationAttachmentService attachmentService,
            CurrentUserProvider currentUserProvider) {
        this.attachmentService = attachmentService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasAnyPermission(authentication, 'EXPENSE_APPLICATION_READ_OWN', 'EXPENSE_APPLICATION_APPROVE')")
    public List<ExpenseApplicationAttachmentResponse> list(
            @PathVariable UUID applicationId, Authentication authentication) {
        AppUser user = current(authentication);
        var result = attachmentService.list(applicationId, user);
        return result.attachments().stream()
                .map(attachment -> ExpenseApplicationAttachmentResponse.from(
                        attachment, result.deletable(attachment.getId())))
                .toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')")
    public ExpenseApplicationAttachmentResponse upload(
            @PathVariable UUID applicationId,
            @RequestPart(value = "file", required = false) MultipartFile file,
            Authentication authentication) {
        AppUser user = current(authentication);
        return ExpenseApplicationAttachmentResponse.from(
                attachmentService.upload(applicationId, file, user), true);
    }

    @GetMapping("/{attachmentId}/content")
    @PreAuthorize("@permissionAuthorizer.hasAnyPermission(authentication, 'EXPENSE_APPLICATION_READ_OWN', 'EXPENSE_APPLICATION_APPROVE')")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable UUID applicationId,
            @PathVariable UUID attachmentId,
            @RequestParam(defaultValue = "false") boolean download,
            Authentication authentication) {
        OpenedExpenseAttachment opened = attachmentService.open(
                applicationId, attachmentId, current(authentication));
        ContentDisposition disposition = ContentDisposition
                .builder(download ? "attachment" : "inline")
                .filename(opened.attachment().getOriginalFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(opened.attachment().getContentType()))
                .contentLength(opened.content().length())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new InputStreamResource(opened.content().stream()));
    }

    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')")
    public void delete(
            @PathVariable UUID applicationId,
            @PathVariable UUID attachmentId,
            Authentication authentication) {
        attachmentService.delete(applicationId, attachmentId, current(authentication));
    }

    private AppUser current(Authentication authentication) {
        return currentUserProvider.getRequiredUser(authentication).user();
    }
}
