package jp.co.sdcj.workflow.config;

import com.azure.ai.documentintelligence.DocumentIntelligenceClient;
import com.azure.ai.documentintelligence.DocumentIntelligenceClientBuilder;
import com.azure.ai.documentintelligence.DocumentIntelligenceServiceVersion;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!manual-seed")
public class DocumentIntelligenceConfiguration {

    public static final DocumentIntelligenceServiceVersion SERVICE_VERSION =
            DocumentIntelligenceServiceVersion.V2024_11_30;

    @Bean
    @ConditionalOnExpression("""
            '${workflow.document-analysis.enabled:false}' == 'true' &&
            '${workflow.document-analysis.execution-mode:disabled}'.equalsIgnoreCase('azure') &&
            '${workflow.document-analysis.document-intelligence.enabled:false}' == 'true'
            """)
    DocumentIntelligenceClient documentIntelligenceClient(
            DocumentAnalysisProperties properties) {
        DocumentAnalysisProperties.Provider provider = properties.documentIntelligence();
        requireSupportedApiVersion(provider.apiVersion());
        return new DocumentIntelligenceClientBuilder()
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
        if (!DocumentAnalysisProperties.DOCUMENT_INTELLIGENCE_API_VERSION.equals(apiVersion)) {
            throw new IllegalStateException(
                    "Unsupported Document Intelligence API version: " + apiVersion);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
