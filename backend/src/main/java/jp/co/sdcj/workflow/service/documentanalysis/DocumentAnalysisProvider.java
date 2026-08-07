package jp.co.sdcj.workflow.service.documentanalysis;

import jp.co.sdcj.workflow.domain.DocumentAnalysisProviderType;

public interface DocumentAnalysisProvider {

    boolean supports(DocumentAnalysisProviderType provider);

    DocumentAnalysisProviderResult analyze(DocumentAnalysisProviderRequest request);
}
