package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.DocumentIntelligenceServiceVersion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class DocumentIntelligenceConfigurationTest {

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
    void usesServiceApiVersion20241130() {
        assertThat(DocumentIntelligenceConfiguration.SERVICE_VERSION)
                .isEqualTo(DocumentIntelligenceServiceVersion.V2024_11_30);
        assertThat(DocumentIntelligenceConfiguration.SERVICE_VERSION.getVersion())
                .isEqualTo("2024-11-30");
    }

    @Test
    void fakeModeDoesNotCreateClient() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=fake",
                        "workflow.document-analysis.document-intelligence.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DocumentIntelligenceClient.class));
    }

    @Test
    void azureModeWithDocumentIntelligenceDisabledDoesNotCreateClient() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure")
                .run(context -> assertThat(context).doesNotHaveBean(DocumentIntelligenceClient.class));
    }

    @Test
    void azureModeWithDocumentIntelligenceEnabledCreatesSingletonClient() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.document-intelligence.enabled=true",
                        "workflow.document-analysis.document-intelligence.endpoint=https://di.example.test")
                .run(context -> assertThat(context).hasSingleBean(DocumentIntelligenceClient.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentAnalysisProperties.class)
    @Import(DocumentIntelligenceConfiguration.class)
    static class TestConfig {
    }
}
