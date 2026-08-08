package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.ai.contentunderstanding.ContentUnderstandingClient;
import com.azure.ai.contentunderstanding.ContentUnderstandingServiceVersion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ContentUnderstandingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "workflow.document-analysis.max-file-size=10MB",
                    "workflow.document-analysis.max-original-file-name-length=255",
                    "workflow.document-analysis.retention=7d",
                    "workflow.document-analysis.retention-cleanup-interval=1h",
                    "workflow.document-analysis.retention-cleanup-batch-size=50",
                    "workflow.document-analysis.batch-size=2",
                    "workflow.document-analysis.dispatch-interval=2s",
                    "workflow.document-analysis.processing-timeout=30m",
                    "workflow.document-analysis.max-active-jobs-per-user=2",
                    "workflow.document-analysis.max-requests-per-user-per-hour=20",
                    "workflow.document-analysis.azure.managed-identity-client-id=",
                    "workflow.document-analysis.document-intelligence.enabled=false",
                    "workflow.document-analysis.document-intelligence.endpoint=",
                    "workflow.document-analysis.document-intelligence.model-id=prebuilt-layout",
                    "workflow.document-analysis.document-intelligence.api-version=2024-11-30",
                    "workflow.document-analysis.document-intelligence.analysis-timeout=25m",
                    "workflow.document-analysis.content-understanding.enabled=false",
                    "workflow.document-analysis.content-understanding.endpoint=",
                    "workflow.document-analysis.content-understanding.model-id=prebuilt-layout",
                    "workflow.document-analysis.content-understanding.api-version=2025-11-01",
                    "workflow.document-analysis.content-understanding.analysis-timeout=25m",
                    "workflow.document-analysis.storage.connection-string="
                            + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                            + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                    "workflow.document-analysis.storage.input-container-name=document-analysis-input",
                    "workflow.document-analysis.storage.result-container-name=document-analysis-result");

    @Test
    void usesServiceApiVersion20251101() {
        assertThat(ContentUnderstandingConfiguration.SERVICE_VERSION)
                .isEqualTo(ContentUnderstandingServiceVersion.V2025_11_01);
        assertThat(ContentUnderstandingConfiguration.SERVICE_VERSION.getVersion())
                .isEqualTo("2025-11-01");
    }

    @Test
    void fakeModeDoesNotCreateClient() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=fake",
                        "workflow.document-analysis.content-understanding.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(ContentUnderstandingClient.class));
    }

    @Test
    void azureModeWithContentUnderstandingDisabledDoesNotCreateClient() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure")
                .run(context -> assertThat(context).doesNotHaveBean(ContentUnderstandingClient.class));
    }

    @Test
    void azureModeWithContentUnderstandingEnabledCreatesSingletonClient() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.content-understanding.endpoint=https://cu.example.test")
                .run(context -> assertThat(context).hasSingleBean(ContentUnderstandingClient.class));
    }

    @Test
    void managedIdentityClientIdCanBeConfigured() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.content-understanding.endpoint=https://cu.example.test",
                        "workflow.document-analysis.azure.managed-identity-client-id="
                                + "11111111-2222-3333-4444-555555555555")
                .run(context -> assertThat(context).hasSingleBean(ContentUnderstandingClient.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentAnalysisProperties.class)
    @Import(ContentUnderstandingConfiguration.class)
    static class TestConfig {
    }
}
