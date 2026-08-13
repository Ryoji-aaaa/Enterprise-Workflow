package jp.co.sdcj.workflow.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryDraftDetails;
import jp.co.sdcj.workflow.service.ExpenseAutoEntryDraftService;

@RestController
@Profile("!manual-seed")
@RequestMapping("/api/expense-applications")
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class ExpenseAutoEntryDraftController {

    private final ExpenseAutoEntryDraftService service;
    private final CurrentUserProvider currentUserProvider;

    public ExpenseAutoEntryDraftController(
            ExpenseAutoEntryDraftService service,
            CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/from-auto-entry")
    @PreAuthorize("""
            @permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')
            and @permissionAuthorizer.hasPermission(authentication, 'DOCUMENT_ANALYSIS_READ_OWN')
            """)
    public ResponseEntity<ExpenseAutoEntryDraftResponse> create(
            @Valid @RequestBody ExpenseAutoEntryDraftCreateRequest request,
            Authentication authentication) {
        ExpenseAutoEntryDraftDetails details = service.create(request, current(authentication));
        ExpenseAutoEntryDraftResponse response = ExpenseAutoEntryDraftResponse.from(details);
        if (!details.created()) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(URI.create(
                        "/api/expense-applications/%s/auto-entry-draft"
                                .formatted(response.application().id())))
                .body(response);
    }

    @GetMapping("/{applicationId}/auto-entry-draft")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_READ_OWN')")
    public ExpenseAutoEntryDraftResponse get(
            @PathVariable UUID applicationId,
            Authentication authentication) {
        return ExpenseAutoEntryDraftResponse.from(
                service.get(applicationId, current(authentication)));
    }

    @PutMapping("/{applicationId}/auto-entry-draft")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'EXPENSE_APPLICATION_CREATE')")
    public ExpenseAutoEntryDraftResponse update(
            @PathVariable UUID applicationId,
            @Valid @RequestBody ExpenseAutoEntryDraftUpdateRequest request,
            Authentication authentication) {
        return ExpenseAutoEntryDraftResponse.from(
                service.update(applicationId, request, current(authentication)));
    }

    private AppUser current(Authentication authentication) {
        return currentUserProvider.getRequiredUser(authentication).user();
    }
}
