package jp.co.sdcj.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import jp.co.sdcj.workflow.storage.DocumentAnalysisStorage;

class DocumentAnalysisPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "workflow.document-analysis.execution-mode=disabled",
                    "workflow.document-analysis.max-file-size=10MB",
                    "workflow.document-analysis.max-original-file-name-length=255",
                    "workflow.document-analysis.retention=7d",
                    "workflow.document-analysis.batch-size=2",
                    "workflow.document-analysis.dispatch-interval=2s",
                    "workflow.document-analysis.processing-timeout=30m",
                    "workflow.document-analysis.max-active-jobs-per-user=2",
                    "workflow.document-analysis.max-requests-per-user-per-hour=20",
                    "workflow.document-analysis.document-intelligence.enabled=false",
                    "workflow.document-analysis.document-intelligence.model-id=prebuilt-layout",
                    "workflow.document-analysis.document-intelligence.api-version=2024-11-30",
                    "workflow.document-analysis.content-understanding.enabled=false",
                    "workflow.document-analysis.content-understanding.model-id=prebuilt-layout",
                    "workflow.document-analysis.content-understanding.api-version=2025-11-01",
                    "workflow.document-analysis.storage.input-container-name=document-analysis-input",
                    "workflow.document-analysis.storage.result-container-name=document-analysis-result");

    @Test
    void disabledAllowsEmptyStorageCredentialsAndDoesNotCreateStorageBean() {
        contextRunner
                .withPropertyValues("workflow.document-analysis.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentAnalysisProperties.class);
                    assertThat(context).doesNotHaveBean(DocumentAnalysisStorage.class);
                });
    }

    @Test
    void enabledAcceptsConnectionString() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;"
                                + "AccountName=devstoreaccount1;"
                                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsu"
                                + "Fq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
                                + "BlobEndpoint=http://azurite:10000/devstoreaccount1;",
                        "workflow.document-analysis.storage.create-containers=false")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisStorage.class));
    }

    @Test
    void enabledAcceptsEndpointAndManagedIdentityClientId() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.storage.endpoint=https://storage.example.test",
                        "workflow.document-analysis.storage.managed-identity-client-id="
                                + "11111111-2222-3333-4444-555555555555",
                        "workflow.document-analysis.storage.create-containers=false")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisStorage.class));
    }

    @Test
    void enabledRejectsMissingCredentials() {
        contextRunner
                .withPropertyValues("workflow.document-analysis.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void sameInputAndResultContainerNamesAreRejected() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=false",
                        "workflow.document-analysis.storage.result-container-name=document-analysis-input")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentAnalysisProperties.class)
    @Import(DocumentAnalysisStorageConfiguration.class)
    static class TestConfig {
    }
}
