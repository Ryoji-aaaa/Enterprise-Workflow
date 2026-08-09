import { DocumentAnalysisWorkbench } from "@/components/document-analysis/document-analysis-workbench";
import { DOCUMENT_ANALYSIS_PROVIDER_CONFIGS } from "@/lib/document-analysis";

export default function DocumentIntelligencePage() {
  return (
    <DocumentAnalysisWorkbench
      config={DOCUMENT_ANALYSIS_PROVIDER_CONFIGS.DOCUMENT_INTELLIGENCE}
    />
  );
}

