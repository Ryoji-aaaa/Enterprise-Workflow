import { fetchBackend } from "./backend-browser-client.ts";
import type {
  DocumentAnalysisProfile,
  DocumentAnalysisProvider,
} from "./document-analysis.ts";
import type { AutoEntryReviewResponse } from "./auto-entry-review.ts";

export type DocumentAnalysisJobStatus =
  | "QUEUED"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "FAILED_RECOVERY_REQUIRED"
  | "EXPIRED";

export type DocumentAnalysisJob = {
  id: string;
  provider: DocumentAnalysisProvider;
  profile: DocumentAnalysisProfile;
  modelId: string;
  providerApiVersion: string;
  normalizedSchemaVersion: number;
  status: DocumentAnalysisJobStatus;
  originalFileName: string;
  contentType: string;
  fileSize: number;
  attemptCount: number;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  expiresAt: string;
};

export type DocumentAnalysisPage = {
  content: DocumentAnalysisJob[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type DocumentAnalysisViewV1 = {
  schemaVersion: number;
  analysisId: string;
  provider: DocumentAnalysisProvider;
  modelId: string;
  providerApiVersion: string;
  status: "SUCCEEDED";
  documents: Array<{
    markdown?: string;
    paragraphs?: Array<{
      index: number;
      content: string;
      role?: string;
      pageNumber?: number;
      confidence?: number;
      source?: {
        offset?: number;
        length?: number;
        polygon?: Array<{ x: number; y: number }>;
      };
    }>;
    tables?: Array<{
      index: number;
      rowCount: number;
      columnCount: number;
      cells?: Array<{
        rowIndex: number;
        columnIndex: number;
        rowSpan?: number;
        columnSpan?: number;
        kind?: "columnHeader" | "content";
        content?: string;
        pageNumber?: number;
        confidence?: number;
      }>;
    }>;
  }>;
  warnings?: unknown[];
  metrics?: Record<string, unknown>;
};

export const RAW_RESULT_PRETTY_PRINT_MAX_BYTES = 1 * 1024 * 1024;

export type DocumentAnalysisRawResult = {
  text: string;
  byteLength: number;
  formatted: boolean;
};

type ErrorBody = {
  code?: string;
  message?: string;
};

export class DocumentAnalysisApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "DocumentAnalysisApiError";
    this.status = status;
    this.code = code;
  }
}

async function readJson<T>(response: Response): Promise<T> {
  return (await response.json()) as T;
}

export function documentAnalysisSafeErrorMessage(
  status: number,
  code: string,
  fallback: string,
): string {
  if (status === 413) return "ファイルサイズが上限を超えています。";
  if (status === 403) return "この分析機能を利用する権限がありません。";
  if (status === 404) return "分析結果が見つかりません。";
  if (status === 410) return "分析結果の保持期限が切れています。";
  if (status === 503 || code === "BACKEND_UNAVAILABLE") {
    return "現在、分析サービスを利用できません。";
  }
  if (status === 429 && code === "DOCUMENT_ANALYSIS_CONCURRENCY_LIMIT") {
    return "同時に実行できる分析要求数の上限に達しています。";
  }
  if (status === 429 && code === "DOCUMENT_ANALYSIS_RATE_LIMIT") {
    return "分析要求の回数上限に達しています。";
  }
  return fallback;
}

async function parseDocumentAnalysisResponse<T>(
  response: Response,
  fallbackMessage: string,
): Promise<T> {
  if (response.ok) return readJson<T>(response);

  const body = (await response.json().catch(() => ({}))) as ErrorBody;
  const code = body.code ?? "DOCUMENT_ANALYSIS_REQUEST_FAILED";
  throw new DocumentAnalysisApiError(
    response.status,
    code,
    documentAnalysisSafeErrorMessage(response.status, code, body.message ?? fallbackMessage),
  );
}

function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

export function formatDocumentAnalysisRawResult(text: string): DocumentAnalysisRawResult {
  const byteLength = utf8ByteLength(text);
  if (byteLength > RAW_RESULT_PRETTY_PRINT_MAX_BYTES) {
    return { text, byteLength, formatted: false };
  }

  try {
    return {
      text: JSON.stringify(JSON.parse(text), null, 2),
      byteLength,
      formatted: true,
    };
  } catch {
    return { text, byteLength, formatted: false };
  }
}

export async function createDocumentAnalysis(
  provider: DocumentAnalysisProvider,
  file: File,
  signal?: AbortSignal,
  profile?: DocumentAnalysisProfile,
): Promise<DocumentAnalysisJob> {
  const data = new FormData();
  data.append("provider", provider);
  if (profile) data.append("profile", profile);
  data.append("file", file, file.name);
  return parseDocumentAnalysisResponse<DocumentAnalysisJob>(
    await fetchBackend("/api/backend/document-analyses", {
      method: "POST",
      body: data,
      signal,
    }),
    "分析要求を開始できませんでした。",
  );
}

export async function listDocumentAnalyses(
  provider: DocumentAnalysisProvider,
  page = 0,
  size = 10,
  signal?: AbortSignal,
  profile?: DocumentAnalysisProfile,
): Promise<DocumentAnalysisPage> {
  const query = new URLSearchParams({
    provider,
    page: String(page),
    size: String(size),
  });
  if (profile) query.set("profile", profile);
  return parseDocumentAnalysisResponse<DocumentAnalysisPage>(
    await fetchBackend(`/api/backend/document-analyses?${query}`, { signal }),
    "分析履歴を取得できませんでした。",
  );
}

export async function getDocumentAnalysis(
  id: string,
  signal?: AbortSignal,
  profile?: DocumentAnalysisProfile,
): Promise<DocumentAnalysisJob> {
  const query = documentAnalysisProfileQuery(profile);
  return parseDocumentAnalysisResponse<DocumentAnalysisJob>(
    await fetchBackend(`/api/backend/document-analyses/${encodeURIComponent(id)}${query}`, { signal }),
    "分析状態を取得できませんでした。",
  );
}

export async function getDocumentAnalysisView(
  id: string,
  signal?: AbortSignal,
  profile?: DocumentAnalysisProfile,
): Promise<DocumentAnalysisViewV1> {
  const query = documentAnalysisProfileQuery(profile);
  return parseDocumentAnalysisResponse<DocumentAnalysisViewV1>(
    await fetchBackend(`/api/backend/document-analyses/${encodeURIComponent(id)}/view${query}`, { signal }),
    "分析結果を取得できませんでした。",
  );
}

export async function getDocumentAnalysisRawResult(
  id: string,
  signal?: AbortSignal,
  profile?: DocumentAnalysisProfile,
): Promise<DocumentAnalysisRawResult> {
  const query = documentAnalysisProfileQuery(profile);
  const response = await fetchBackend(
    `/api/backend/document-analyses/${encodeURIComponent(id)}/raw-result${query}`,
    { signal },
  );
  if (response.ok) return formatDocumentAnalysisRawResult(await response.text());

  const body = (await response.json().catch(() => ({}))) as ErrorBody;
  const code = body.code ?? "DOCUMENT_ANALYSIS_REQUEST_FAILED";
  throw new DocumentAnalysisApiError(
    response.status,
    code,
    documentAnalysisSafeErrorMessage(response.status, code, body.message ?? "Raw Resultを取得できませんでした。"),
  );
}

export async function getAutoEntryReview(
  id: string,
  signal?: AbortSignal,
): Promise<AutoEntryReviewResponse> {
  return parseDocumentAnalysisResponse<AutoEntryReviewResponse>(
    await fetchBackend(
      `/api/backend/document-analyses/${encodeURIComponent(id)}/auto-entry-review`,
      { signal },
    ),
    "自動入力結果を取得できませんでした。",
  );
}

function documentAnalysisProfileQuery(profile?: DocumentAnalysisProfile): string {
  return profile ? `?profile=${encodeURIComponent(profile)}` : "";
}

export function documentAnalysisSourceUrl(
  id: string,
  profile?: DocumentAnalysisProfile,
): string {
  return `/api/backend/document-analyses/${encodeURIComponent(id)}/source${documentAnalysisProfileQuery(profile)}`;
}
