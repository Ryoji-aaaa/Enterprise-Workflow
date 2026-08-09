package jp.co.sdcj.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jp.co.sdcj.workflow.service.ManagementFailureAuditService;

class ApiExceptionHandlerTest {

    @Test
    void multipartサイズ超過を統一JSONエラーへ変換する() {
        ApiExceptionHandler handler = new ApiExceptionHandler(
                mock(ManagementFailureAuditService.class));

        var response = handler.handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(1024),
                new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isEqualTo(new ApiError(
                "EXPENSE_ATTACHMENT_TOO_LARGE",
                "ファイルサイズが上限を超えています。"));
    }

    @Test
    void documentAnalysisのmultipartサイズ超過は専用コードへ変換する() {
        ApiExceptionHandler handler = new ApiExceptionHandler(
                mock(ManagementFailureAuditService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/document-analyses");

        var response = handler.handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(1024),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isEqualTo(new ApiError(
                "DOCUMENT_ANALYSIS_TOO_LARGE",
                "ファイルサイズが上限を超えています。"));
    }
}
