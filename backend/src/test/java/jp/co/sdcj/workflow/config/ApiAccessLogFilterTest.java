package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import jp.co.sdcj.workflow.service.RequestAuditMetadata;
import jp.co.sdcj.workflow.service.RequestAuditMetadataProvider;

class ApiAccessLogFilterTest {

    private final RequestAuditMetadataProvider metadataProvider =
            new RequestAuditMetadataProvider();
    private final ApiAccessLogFilter filter = new ApiAccessLogFilter(metadataProvider);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger().detachAppender(appender);
        appender.stop();
    }

    @Test
    void successfulApiRequestLogsOnlySafeFieldsWithoutChangingTheResponse() throws Exception {
        UUID requestId = UUID.randomUUID();
        MockHttpServletRequest request = request("GET", "/api/me");
        request.addHeader("X-Request-Id", requestId.toString());
        request.addHeader("Authorization", "Bearer private-authorization-value");
        request.addHeader("Cookie", "session=private-cookie-value");
        request.setQueryString("token=private-query-value");
        request.setContent("private-request-body".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            ((HttpServletResponse) servletResponse).setStatus(200);
            servletResponse.getWriter().write("private-response-body");
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("private-response-body");
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .startsWith("event=http_access requestId=" + requestId
                            + " method=GET path=/api/me status=200 durationMs=")
                    .doesNotContain(
                            "private-authorization-value",
                            "private-cookie-value",
                            "private-query-value",
                            "private-request-body",
                            "private-response-body",
                            "token=");
            assertThat(event.getFormattedMessage()).matches(".* durationMs=\\d+$");
        });
    }

    @Test
    void returned4xxAnd5xxUseExpectedLevels() throws Exception {
        filter.doFilter(request("PATCH", "/api/admin/users"), new MockHttpServletResponse(),
                (servletRequest, servletResponse) ->
                        ((HttpServletResponse) servletResponse).setStatus(400));
        filter.doFilter(request("POST", "/api/expense-applications"),
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) ->
                        ((HttpServletResponse) servletResponse).setStatus(503));

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("status=400");
        assertThat(appender.list.get(1).getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.list.get(1).getFormattedMessage()).contains("status=503");
    }

    @Test
    void unhandledExceptionLogs500OnceWithoutLoggingTheException() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/failure");

        assertThatThrownBy(() -> filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                    throw new ServletException("private-exception-message");
                }))
                .isInstanceOf(ServletException.class)
                .hasMessage("private-exception-message");

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains("method=GET", "path=/api/failure", "status=500")
                    .doesNotContain("private-exception-message");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void invalidRequestIdIsReplacedAndCachedForTheRequest() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/me");
        request.addHeader("X-Request-Id", "not-a-uuid");

        filter.doFilter(request, new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                });

        RequestAuditMetadata first = metadataProvider.current(request);
        RequestAuditMetadata second = metadataProvider.current(request);
        assertThat(first).isSameAs(second);
        assertThat(UUID.fromString(first.requestId().toString())).isEqualTo(first.requestId());
        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .contains("requestId=" + first.requestId())
                        .doesNotContain("not-a-uuid"));
    }

    @Test
    void credentialLikePathIsRedactedAndHealthIsNotLogged() throws Exception {
        filter.doFilter(request("GET", "/api/Authorization=private-path-value"),
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                });
        filter.doFilter(request("GET", "/actuator/health/readiness"),
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                });

        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .contains("path=[REDACTED]")
                        .doesNotContain("private-path-value", "/actuator/health"));
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(ApiAccessLogFilter.class);
    }

}
