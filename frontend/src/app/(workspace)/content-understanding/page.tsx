import { DocumentAnalysisWorkbench } from "@/components/document-analysis/document-analysis-workbench";
import { DOCUMENT_ANALYSIS_PROVIDER_CONFIGS } from "@/lib/document-analysis";

export default function ContentUnderstandingPage() {
  return (
    <DocumentAnalysisWorkbench
      config={DOCUMENT_ANALYSIS_PROVIDER_CONFIGS.CONTENT_UNDERSTANDING}
    />
  );
}

