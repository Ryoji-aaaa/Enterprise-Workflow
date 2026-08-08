package jp.co.sdcj.workflow.service.documentanalysis;

import java.util.List;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;

@Component
public class DocumentAnalysisProviderRegistry {

    private final List<DocumentAnalysisProvider> providers;

    public DocumentAnalysisProviderRegistry(List<DocumentAnalysisProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @PostConstruct
    void validateProviderUniqueness() {
        for (DocumentAnalysisProviderType providerType : DocumentAnalysisProviderType.values()) {
            long count = providers.stream()
                    .filter(provider -> provider.supports(providerType))
                    .count();
            if (count > 1) {
                throw new IllegalStateException(
                        "Expected at most one document analysis provider for " + providerType);
            }
        }
    }

    public boolean isAvailable(DocumentAnalysisProviderType providerType) {
        List<DocumentAnalysisProvider> matches = providers.stream()
                .filter(provider -> provider.supports(providerType))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Expected at most one document analysis provider for " + providerType);
        }
        return matches.size() == 1;
    }

    public DocumentAnalysisProvider providerFor(DocumentAnalysisProviderType providerType) {
        List<DocumentAnalysisProvider> matches = providers.stream()
                .filter(provider -> provider.supports(providerType))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one document analysis provider for " + providerType);
        }
        return matches.get(0);
    }
}
