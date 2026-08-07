package jp.co.sdcj.workflow.api;

import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.servlet.http.HttpServletRequest;
import jp.co.sdcj.workflow.service.ManagementFailureAuditService;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final ManagementFailureAuditService managementFailureAuditService;

    public ApiExceptionHandler(ManagementFailureAuditService managementFailureAuditService) {
        this.managementFailureAuditService = managementFailureAuditService;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(
            ApiException exception,
            HttpServletRequest request) {
        String auditReason = exception.getStatus().is5xxServerError()
                ? ManagementFailureAuditService.INTERNAL_SERVER_ERROR
                : exception.getCode();
        recordManagementFailure(request, auditReason);
        return ResponseEntity
                .status(exception.getStatus())
                .body(new ApiError(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidRequest(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        recordManagementFailure(request, "INVALID_REQUEST");
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "入力内容を確認してください。"));
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request) {
        recordManagementFailure(request, "INVALID_REQUEST");
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "入力内容を確認してください。"));
    }

    @ExceptionHandler({
        DataIntegrityViolationException.class,
        ObjectOptimisticLockingFailureException.class
    })
    ResponseEntity<ApiError> handleConflict(
            RuntimeException exception,
            HttpServletRequest request) {
        recordManagementFailure(request, "CONFLICT");
        return ResponseEntity.status(409)
                .body(new ApiError("CONFLICT", "他の更新と競合しました。再読み込みしてください。"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleMaxUploadSizeExceeded(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        if (request.getRequestURI() != null
                && request.getRequestURI().startsWith("/api/document-analyses")) {
            recordManagementFailure(request, "DOCUMENT_ANALYSIS_TOO_LARGE");
            return ResponseEntity.status(413)
                    .body(new ApiError(
                            "DOCUMENT_ANALYSIS_TOO_LARGE",
                            "ファイルサイズが上限を超えています。"));
        }
        recordManagementFailure(request, "EXPENSE_ATTACHMENT_TOO_LARGE");
        return ResponseEntity.status(413)
                .body(new ApiError(
                        "EXPENSE_ATTACHMENT_TOO_LARGE",
                        "ファイルサイズが上限を超えています。"));
    }

    private void recordManagementFailure(HttpServletRequest request, String reason) {
        managementFailureAuditService.recordOnce(request, reason);
    }
}
