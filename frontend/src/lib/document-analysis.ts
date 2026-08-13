import type {
  DocumentAnalysisJob,
  DocumentAnalysisJobStatus,
  DocumentAnalysisViewV1,
} from "./document-analysis-api.ts";

export type DocumentAnalysisProvider =
  | "DOCUMENT_INTELLIGENCE"
  | "CONTENT_UNDERSTANDING";

export type DocumentAnalysisProfile = "GENERAL" | "AUTO_ENTRY";

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
  analysisId: string;
  provider: DocumentAnalysisProvider;
  modelId: string;
  providerApiVersion: string;
  markdown: string;
  paragraphs: DocumentAnalysisParagraph[];
  tables: DocumentAnalysisTable[];
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
      job: null;
      result: null;
      error: null;
      completedStatuses: DocumentAnalysisStatus[];
    }
  | {
      status: "selected" | "uploading";
      selectedFile: AnalyzableFile;
      job: null;
      result: null;
      error: null;
      completedStatuses: DocumentAnalysisStatus[];
    }
  | {
      status: "queued" | "running";
      selectedFile: AnalyzableFile;
      job: DocumentAnalysisJob;
      result: null;
      error: null;
      completedStatuses: DocumentAnalysisStatus[];
    }
  | {
      status: "succeeded";
      selectedFile: AnalyzableFile;
      job: DocumentAnalysisJob;
      result: DocumentAnalysisResult;
      error: null;
      completedStatuses: DocumentAnalysisStatus[];
    }
  | {
      status: "failed";
      selectedFile: AnalyzableFile | null;
      job: DocumentAnalysisJob | null;
      result: null;
      error: string;
      completedStatuses: DocumentAnalysisStatus[];
    };

export type DocumentAnalysisEvent =
  | { type: "select"; file: AnalyzableFile; validation: DocumentValidationResult }
  | { type: "reject"; message: string }
  | { type: "clear" }
  | { type: "upload" }
  | { type: "job"; job: DocumentAnalysisJob }
  | { type: "view"; job: DocumentAnalysisJob; result: DocumentAnalysisResult }
  | { type: "fail"; message: string; job?: DocumentAnalysisJob };

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
  job: null,
  result: null,
  error: null,
  completedStatuses: [],
};

function jobFile(job: DocumentAnalysisJob): AnalyzableFile {
  return {
    name: job.originalFileName,
    size: job.fileSize,
    type: job.contentType,
  };
}

function completedStatuses(status: DocumentAnalysisStatus): DocumentAnalysisStatus[] {
  if (status === "selected") return ["selected"];
  if (status === "uploading") return ["selected", "uploading"];
  if (status === "queued") return ["selected", "uploading", "queued"];
  if (status === "running") return ["selected", "uploading", "queued", "running"];
  if (status === "succeeded") {
    return ["selected", "uploading", "queued", "running", "succeeded"];
  }
  return [];
}

export function serverStatusToDocumentAnalysisStatus(
  status: DocumentAnalysisJobStatus,
): DocumentAnalysisStatus {
  if (status === "QUEUED") return "queued";
  if (status === "RUNNING") return "running";
  if (status === "SUCCEEDED") return "succeeded";
  return "failed";
}

export function documentAnalysisJobErrorMessage(job: DocumentAnalysisJob): string {
  if (job.status === "FAILED_RECOVERY_REQUIRED") {
    return "分析処理の復旧が必要です。しばらくしても解消しない場合は管理者へ連絡してください。";
  }
  if (job.status === "EXPIRED") {
    return "分析結果の保持期限が切れています。";
  }
  return job.errorMessage ?? "分析処理に失敗しました。";
}

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
          job: null,
          result: null,
          error: event.validation.message,
          completedStatuses: [],
        };
      }
      return {
        status: "selected",
        selectedFile: event.file,
        job: null,
        result: null,
        error: null,
        completedStatuses: ["selected"],
      };
    case "reject":
      return {
        status: "failed",
        selectedFile: null,
        job: null,
        result: null,
        error: event.message,
        completedStatuses: [],
      };
    case "clear":
      return initialDocumentAnalysisState;
    case "upload":
      if (!state.selectedFile) {
        return state;
      }
      return {
        status: "uploading",
        selectedFile: state.selectedFile,
        job: null,
        result: null,
        error: null,
        completedStatuses: ["selected", "uploading"],
      };
    case "job": {
      const status = serverStatusToDocumentAnalysisStatus(event.job.status);
      if (status === "failed") {
        return {
          status: "failed",
          selectedFile: jobFile(event.job),
          job: event.job,
          result: null,
          error: documentAnalysisJobErrorMessage(event.job),
          completedStatuses: completedStatuses("running"),
        };
      }
      if (status === "succeeded") {
        return {
          status: "running",
          selectedFile: jobFile(event.job),
          job: event.job,
          result: null,
          error: null,
          completedStatuses: completedStatuses("running"),
        };
      }
      if (status === "queued" || status === "running") {
        return {
          status,
          selectedFile: jobFile(event.job),
          job: event.job,
          result: null,
          error: null,
          completedStatuses: completedStatuses(status),
        };
      }
      return state;
    }
    case "view":
      return {
        status: "succeeded",
        selectedFile: jobFile(event.job),
        job: event.job,
        result: event.result,
        error: null,
        completedStatuses: completedStatuses("succeeded"),
      };
    case "fail":
      return {
        status: "failed",
        selectedFile: event.job ? jobFile(event.job) : state.selectedFile,
        job: event.job ?? state.job,
        result: null,
        error: event.message,
        completedStatuses: state.completedStatuses,
      };
  }
}

export function isDocumentAnalysisProcessing(status: DocumentAnalysisStatus): boolean {
  return status === "uploading" || status === "queued" || status === "running";
}

export function mapDocumentAnalysisViewV1(
  view: DocumentAnalysisViewV1,
): DocumentAnalysisResult {
  if (view.schemaVersion !== 1) {
    throw new Error("Unsupported document analysis result schema.");
  }

  return {
    analysisId: view.analysisId,
    provider: view.provider,
    modelId: view.modelId,
    providerApiVersion: view.providerApiVersion,
    markdown: view.documents.map((document) => document.markdown ?? "").join("\n\n").trim(),
    paragraphs: view.documents.flatMap((document) =>
      (document.paragraphs ?? []).map((paragraph) => ({
        id: `paragraph-${paragraph.index}`,
        content: paragraph.content,
        role: paragraph.role ?? "content",
        pageNumber: paragraph.pageNumber ?? 1,
        confidence: paragraph.confidence ?? 0,
        polygon: paragraph.source?.polygon,
        span: paragraph.source?.offset === undefined || paragraph.source.length === undefined
          ? undefined
          : {
              offset: paragraph.source.offset,
              length: paragraph.source.length,
            },
      })),
    ),
    tables: view.documents.flatMap((document) =>
      (document.tables ?? []).map((table) => ({
        id: `table-${table.index}`,
        rowCount: table.rowCount,
        columnCount: table.columnCount,
        cells: (table.cells ?? []).map((cell) => ({
          rowIndex: cell.rowIndex,
          columnIndex: cell.columnIndex,
          rowSpan: cell.rowSpan ?? 1,
          columnSpan: cell.columnSpan ?? 1,
          kind: cell.kind ?? "content",
          content: cell.content ?? "",
          pageNumber: cell.pageNumber ?? 1,
          confidence: cell.confidence ?? 0,
        })),
      })),
    ),
  };
}
