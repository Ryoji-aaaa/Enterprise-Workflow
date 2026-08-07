export type DocumentAnalysisProvider =
  | "DOCUMENT_INTELLIGENCE"
  | "CONTENT_UNDERSTANDING";

export type DocumentAnalysisProviderConfig = {
  provider: DocumentAnalysisProvider;
  title: string;
  description: string;
  route: string;
};

export type AnalyzableFile = {
  name: string;
  size: number;
  type: string;
};

export type DocumentValidationResult =
  | { valid: true }
  | { valid: false; message: string };

export type DocumentAnalysisParagraph = {
  id: string;
  content: string;
  role: string;
  pageNumber: number;
  confidence: number;
  polygon?: Array<{ x: number; y: number }>;
  span?: { offset: number; length: number };
};

export type DocumentAnalysisTableCell = {
  rowIndex: number;
  columnIndex: number;
  rowSpan: number;
  columnSpan: number;
  kind: "columnHeader" | "content";
  content: string;
  pageNumber: number;
  confidence: number;
};

export type DocumentAnalysisTable = {
  id: string;
  rowCount: number;
  columnCount: number;
  cells: DocumentAnalysisTableCell[];
};

export type DocumentAnalysisResult = {
  provider: DocumentAnalysisProvider;
  markdown: string;
  paragraphs: DocumentAnalysisParagraph[];
  tables: DocumentAnalysisTable[];
  rawResult: Record<string, unknown>;
};

export type DocumentAnalysisStatus =
  | "idle"
  | "selected"
  | "uploading"
  | "queued"
  | "running"
  | "succeeded"
  | "failed";

export type DocumentAnalysisState =
  | {
      status: "idle";
      selectedFile: null;
      result: null;
      error: null;
      completedStatuses: DocumentAnalysisStatus[];
    }
  | {
      status: "selected" | "uploading" | "queued" | "running";
      selectedFile: AnalyzableFile;
      result: null;
      error: null;
      completedStatuses: DocumentAnalysisStatus[];
    }
  | {
      status: "succeeded";
      selectedFile: AnalyzableFile;
      result: DocumentAnalysisResult;
      error: null;
      completedStatuses: DocumentAnalysisStatus[];
    }
  | {
      status: "failed";
      selectedFile: AnalyzableFile | null;
      result: null;
      error: string;
      completedStatuses: DocumentAnalysisStatus[];
    };

export type DocumentAnalysisEvent =
  | { type: "select"; file: AnalyzableFile; validation: DocumentValidationResult }
  | { type: "reject"; message: string }
  | { type: "clear" }
  | { type: "runFixture"; result: DocumentAnalysisResult };

export const DOCUMENT_ANALYSIS_MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;

export const DOCUMENT_ANALYSIS_ACCEPT = [
  ".pdf",
  ".jpg",
  ".jpeg",
  ".png",
  "application/pdf",
  "image/jpeg",
  "image/png",
].join(",");

export const DOCUMENT_ANALYSIS_PROVIDER_CONFIGS = {
  DOCUMENT_INTELLIGENCE: {
    provider: "DOCUMENT_INTELLIGENCE",
    title: "Document Intelligence",
    description: "Azure AI Document Intelligence Layoutによる文書解析",
    route: "/document-intelligence",
  },
  CONTENT_UNDERSTANDING: {
    provider: "CONTENT_UNDERSTANDING",
    title: "Content Understanding",
    description: "Azure AI Content Understanding Layoutによる文書解析",
    route: "/content-understanding",
  },
} as const satisfies Record<DocumentAnalysisProvider, DocumentAnalysisProviderConfig>;

const acceptedMimeTypes = new Set(["application/pdf", "image/jpeg", "image/png"]);

export function validateDocumentFile(file: Pick<AnalyzableFile, "type" | "size">): DocumentValidationResult {
  if (!acceptedMimeTypes.has(file.type)) {
    return { valid: false, message: "対応形式はPDF、JPEG、PNGです。" };
  }

  if (file.size > DOCUMENT_ANALYSIS_MAX_FILE_SIZE_BYTES) {
    return { valid: false, message: "ファイルサイズは10 MiB以下にしてください。" };
  }

  return { valid: true };
}

export function validateSingleDocumentSelection(files: ArrayLike<AnalyzableFile>): DocumentValidationResult {
  if (files.length > 1) {
    return { valid: false, message: "一度に分析できるファイルは1件です。" };
  }

  if (files.length === 0) {
    return { valid: false, message: "分析するファイルを選択してください。" };
  }

  return validateDocumentFile(files[0]);
}

export const initialDocumentAnalysisState: DocumentAnalysisState = {
  status: "idle",
  selectedFile: null,
  result: null,
  error: null,
  completedStatuses: [],
};

export function documentAnalysisReducer(
  state: DocumentAnalysisState,
  event: DocumentAnalysisEvent,
): DocumentAnalysisState {
  switch (event.type) {
    case "select":
      if (!event.validation.valid) {
        return {
          status: "failed",
          selectedFile: null,
          result: null,
          error: event.validation.message,
          completedStatuses: [],
        };
      }
      return {
        status: "selected",
        selectedFile: event.file,
        result: null,
        error: null,
        completedStatuses: ["selected"],
      };
    case "reject":
      return {
        status: "failed",
        selectedFile: null,
        result: null,
        error: event.message,
        completedStatuses: [],
      };
    case "clear":
      return initialDocumentAnalysisState;
    case "runFixture":
      if (!state.selectedFile) {
        return state;
      }
      return {
        status: "succeeded",
        selectedFile: state.selectedFile,
        result: event.result,
        error: null,
        completedStatuses: ["selected", "uploading", "queued", "running", "succeeded"],
      };
  }
}

export function isDocumentAnalysisProcessing(status: DocumentAnalysisStatus): boolean {
  return status === "uploading" || status === "queued" || status === "running";
}
