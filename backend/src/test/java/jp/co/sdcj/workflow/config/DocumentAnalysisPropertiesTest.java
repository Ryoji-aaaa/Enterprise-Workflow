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
                    "workflow.document-analysis.storage.input-container-name=document-analysis-input",
                    "workflow.document-analysis.storage.result-container-name=document-analysis-result");

    @Test
    void disabledAllowsEmptyStorageCredentialsAndDoesNotCreateStorageBean() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=false",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.document-intelligence.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentAnalysisProperties.class);
                    assertThat(context).doesNotHaveBean(DocumentAnalysisStorage.class);
                });
    }

    @Test
    void contentUnderstandingAutoEntrySettingsCanBeOverridden() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=false",
                        "workflow.document-analysis.content-understanding.auto-entry-analyzer-id="
                                + "custom-analyzer",
                        "workflow.document-analysis.content-understanding."
                                + "auto-entry-completion-model-deployment-name=completion-deployment",
                        "workflow.document-analysis.content-understanding."
                                + "auto-entry-embedding-model-deployment-name=embedding-deployment")
                .run(context -> {
                    DocumentAnalysisProperties.Provider contentUnderstanding = context
                            .getBean(DocumentAnalysisProperties.class)
                            .contentUnderstanding();
                    assertThat(contentUnderstanding.autoEntryAnalyzerId()).isEqualTo("custom-analyzer");
                    assertThat(contentUnderstanding.autoEntryCompletionModelDeploymentName())
                            .isEqualTo("completion-deployment");
                    assertThat(contentUnderstanding.autoEntryEmbeddingModelDeploymentName())
                            .isEqualTo("embedding-deployment");
                });
    }

    @Test
    void contentUnderstandingAutoEntrySettingsHaveSafeDefaults() {
        contextRunner
                .withPropertyValues("workflow.document-analysis.enabled=false")
                .run(context -> {
                    DocumentAnalysisProperties.Provider contentUnderstanding = context
                            .getBean(DocumentAnalysisProperties.class)
                            .contentUnderstanding();
                    assertThat(contentUnderstanding.autoEntryAnalyzerId())
                            .isEqualTo("enterprise_workflow_auto_entry_v2.1");
                    assertThat(contentUnderstanding.autoEntryCompletionModelDeploymentName())
                            .isEqualTo("auto-entry-gpt-5-2");
                    assertThat(contentUnderstanding.autoEntryEmbeddingModelDeploymentName())
                            .isEqualTo("auto-entry-text-embedding-3-large");
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
    void fakeModeAllowsDocumentIntelligenceWithoutAzureEndpoint() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=fake",
                        "workflow.document-analysis.document-intelligence.enabled=true",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisProperties.class));
    }

    @Test
    void fakeModeAllowsContentUnderstandingWithoutAzureEndpoint() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=fake",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisProperties.class));
    }

    @Test
    void disabledExecutionAllowsDocumentIntelligenceWithoutAzureEndpoint() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=disabled",
                        "workflow.document-analysis.document-intelligence.enabled=true",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisProperties.class));
    }

    @Test
    void disabledExecutionAllowsContentUnderstandingWithoutAzureEndpoint() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=disabled",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisProperties.class));
    }

    @Test
    void azureContentUnderstandingDisabledAllowsEmptyEndpoint() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.content-understanding.enabled=false",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisProperties.class));
    }

    @Test
    void azureDocumentIntelligenceRequiresEndpoint() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.document-intelligence.enabled=true",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void azureDocumentIntelligenceRejectsWrongApiVersion() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.document-intelligence.enabled=true",
                        "workflow.document-analysis.document-intelligence.endpoint=https://di.example.test",
                        "workflow.document-analysis.document-intelligence.api-version=2024-07-31-preview",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void azureDocumentIntelligenceRejectsInvalidAnalysisTimeout() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.document-intelligence.enabled=true",
                        "workflow.document-analysis.document-intelligence.endpoint=https://di.example.test",
                        "workflow.document-analysis.document-intelligence.analysis-timeout=30m",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void azureDocumentIntelligenceAcceptsValidEndpointAndOptionalIdentity() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.document-intelligence.enabled=true",
                        "workflow.document-analysis.document-intelligence.endpoint=https://di.example.test",
                        "workflow.document-analysis.azure.managed-identity-client-id="
                                + "11111111-2222-3333-4444-555555555555",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisProperties.class));
    }

    @Test
    void azureContentUnderstandingRequiresEndpoint() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void azureContentUnderstandingRejectsWrongApiVersion() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.content-understanding.endpoint=https://cu.example.test",
                        "workflow.document-analysis.content-understanding.api-version=2025-05-01-preview",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void azureContentUnderstandingRejectsNonPositiveAnalysisTimeout() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.content-understanding.endpoint=https://cu.example.test",
                        "workflow.document-analysis.content-understanding.analysis-timeout=0s",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void azureContentUnderstandingRejectsAnalysisTimeoutAtProcessingTimeout() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.content-understanding.endpoint=https://cu.example.test",
                        "workflow.document-analysis.content-understanding.analysis-timeout=30m",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void azureContentUnderstandingAcceptsValidEndpointAndOptionalIdentity() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=true",
                        "workflow.document-analysis.execution-mode=azure",
                        "workflow.document-analysis.content-understanding.enabled=true",
                        "workflow.document-analysis.content-understanding.endpoint=https://cu.example.test",
                        "workflow.document-analysis.azure.managed-identity-client-id="
                                + "11111111-2222-3333-4444-555555555555",
                        "workflow.document-analysis.storage.connection-string="
                                + "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                                + "AccountKey=test;BlobEndpoint=http://azurite:10000/devstoreaccount1;")
                .run(context -> assertThat(context).hasSingleBean(DocumentAnalysisProperties.class));
    }

    @Test
    void sameInputAndResultContainerNamesAreRejected() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=false",
                        "workflow.document-analysis.storage.result-container-name=document-analysis-input")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void retentionCleanupIntervalMustBePositive() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=false",
                        "workflow.document-analysis.retention-cleanup-interval=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void retentionCleanupBatchSizeMustBePositive() {
        contextRunner
                .withPropertyValues(
                        "workflow.document-analysis.enabled=false",
                        "workflow.document-analysis.retention-cleanup-batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentAnalysisProperties.class)
    @Import(DocumentAnalysisStorageConfiguration.class)
    static class TestConfig {
    }
}
