package jp.co.sdcj.workflow.config;

import com.azure.ai.contentunderstanding.ContentUnderstandingClient;
import com.azure.ai.contentunderstanding.ContentUnderstandingClientBuilder;
import com.azure.ai.contentunderstanding.ContentUnderstandingServiceVersion;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!manual-seed")
public class ContentUnderstandingConfiguration {

    public static final ContentUnderstandingServiceVersion SERVICE_VERSION =
            ContentUnderstandingServiceVersion.V2025_11_01;

    @Bean
    @ConditionalOnExpression("""
            '${workflow.document-analysis.enabled:false}' == 'true' &&
            '${workflow.document-analysis.execution-mode:disabled}'.equalsIgnoreCase('azure') &&
            '${workflow.document-analysis.content-understanding.enabled:false}' == 'true'
            """)
    ContentUnderstandingClient contentUnderstandingClient(
            DocumentAnalysisProperties properties) {
        DocumentAnalysisProperties.Provider provider = properties.contentUnderstanding();
        requireSupportedApiVersion(provider.apiVersion());
        return new ContentUnderstandingClientBuilder()
                .endpoint(provider.endpoint())
                .credential(credential(properties.azure()))
                .serviceVersion(SERVICE_VERSION)
                .buildClient();
    }

    private static TokenCredential credential(DocumentAnalysisProperties.Azure azure) {
        DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();
        if (hasText(azure.managedIdentityClientId())) {
            builder.managedIdentityClientId(azure.managedIdentityClientId());
        }
        return builder.build();
    }

    public static void requireSupportedApiVersion(String apiVersion) {
        if (!DocumentAnalysisProperties.CONTENT_UNDERSTANDING_API_VERSION.equals(apiVersion)) {
            throw new IllegalStateException(
                    "Unsupported Content Understanding API version: " + apiVersion);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
