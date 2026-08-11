package jp.co.sdcj.workflow.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jp.co.sdcj.workflow.domain.AppUser;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProfile;
import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;
import jp.co.sdcj.workflow.service.CurrentUserProvider;
import jp.co.sdcj.workflow.service.documentanalysis.DocumentAnalysisService;
import jp.co.sdcj.workflow.service.documentanalysis.OpenedDocumentAnalysisContent;

@RestController
@RequestMapping("/api/document-analyses")
@ConditionalOnProperty(prefix = "workflow.document-analysis", name = "enabled", havingValue = "true")
public class DocumentAnalysisController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentAnalysisService service;
    private final CurrentUserProvider currentUserProvider;

    public DocumentAnalysisController(
            DocumentAnalysisService service,
            CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@permissionAuthorizer.hasAnyPermission(authentication, 'DOCUMENT_INTELLIGENCE_ANALYZE', 'CONTENT_UNDERSTANDING_ANALYZE')")
    public ResponseEntity<DocumentAnalysisResponse> create(
            @RequestPart("provider") String provider,
            @RequestPart(value = "profile", required = false) String profile,
            @RequestPart(value = "file", required = false) MultipartFile file,
            Authentication authentication) {
        AppUser user = current(authentication);
        DocumentAnalysisResponse response = DocumentAnalysisResponse.from(
                service.create(parseProvider(provider), parseProfile(profile), file, user));
        return ResponseEntity.accepted()
                .location(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{analysisId}")
                        .buildAndExpand(response.id())
                        .toUri())
                .body(response);
    }

    @GetMapping
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'DOCUMENT_ANALYSIS_READ_OWN')")
    public PageResponse<DocumentAnalysisResponse> mine(
            @RequestParam(required = false) DocumentAnalysisProviderType provider,
            @RequestParam(required = false) String profile,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        return PageResponse.from(service.listMine(
                provider,
                parseProfile(profile),
                current(authentication),
                bounded(pageable)).map(DocumentAnalysisResponse::from));
    }

    @GetMapping("/{analysisId}")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'DOCUMENT_ANALYSIS_READ_OWN')")
    public DocumentAnalysisResponse get(
            @PathVariable UUID analysisId,
            @RequestParam(required = false) String profile,
            Authentication authentication) {
        return DocumentAnalysisResponse.from(service.getMine(
                analysisId, parseProfile(profile), current(authentication)));
    }

    @GetMapping("/{analysisId}/source")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'DOCUMENT_ANALYSIS_READ_OWN')")
    public ResponseEntity<InputStreamResource> source(
            @PathVariable UUID analysisId,
            @RequestParam(required = false) String profile,
            Authentication authentication) {
        OpenedDocumentAnalysisContent opened = service.openSource(
                analysisId, parseProfile(profile), current(authentication));
        ContentDisposition disposition = ContentDisposition
                .builder("inline")
                .filename(opened.fileName(), StandardCharsets.UTF_8)
                .build();
        return noStore()
                .contentType(MediaType.parseMediaType(opened.content().contentType()))
                .contentLength(opened.content().length())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(opened.content().stream()));
    }

    @GetMapping("/{analysisId}/view")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'DOCUMENT_ANALYSIS_READ_OWN')")
    public ResponseEntity<InputStreamResource> view(
            @PathVariable UUID analysisId,
            @RequestParam(required = false) String profile,
            Authentication authentication) {
        return json(service.openView(analysisId, parseProfile(profile), current(authentication)));
    }

    @GetMapping("/{analysisId}/raw-result")
    @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, 'DOCUMENT_ANALYSIS_READ_OWN')")
    public ResponseEntity<InputStreamResource> rawResult(
            @PathVariable UUID analysisId,
            @RequestParam(required = false) String profile,
            Authentication authentication) {
        return json(service.openRawResult(
                analysisId, parseProfile(profile), current(authentication)));
    }

    private ResponseEntity<InputStreamResource> json(OpenedDocumentAnalysisContent opened) {
        return noStore()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(opened.content().length())
                .body(new InputStreamResource(opened.content().stream()));
    }

    private ResponseEntity.BodyBuilder noStore() {
        return ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate());
    }

    private AppUser current(Authentication authentication) {
        return currentUserProvider.getRequiredUser(authentication).user();
    }

    private static DocumentAnalysisProviderType parseProvider(String provider) {
        try {
            return DocumentAnalysisProviderType.valueOf(provider);
        } catch (RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_ANALYSIS_PROVIDER_REQUIRED",
                    "分析Providerを指定してください。");
        }
    }

    private static DocumentAnalysisProfile parseProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return DocumentAnalysisProfile.GENERAL;
        }
        try {
            return DocumentAnalysisProfile.valueOf(profile);
        } catch (RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_ANALYSIS_PROFILE_INVALID",
                    "分析Profileが不正です。");
        }
    }

    private static Pageable bounded(Pageable pageable) {
        if (pageable.getPageSize() <= MAX_PAGE_SIZE) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
    }
}
