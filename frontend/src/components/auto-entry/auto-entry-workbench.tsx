"use client";

import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import { FileUp, PanelLeft, ScanLine } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { canUseContentUnderstanding } from "@/lib/backend-api";
import type { AutoEntryReviewResponse } from "@/lib/auto-entry-review";
import {
  type AnalyzableFile,
  documentAnalysisReducer,
  initialDocumentAnalysisState,
  isDocumentAnalysisProcessing,
  validateSingleDocumentSelection,
} from "@/lib/document-analysis";
import {
  createDocumentAnalysis,
  documentAnalysisSourceUrl,
  DocumentAnalysisApiError,
  getAutoEntryReview,
  getDocumentAnalysis,
  listDocumentAnalyses,
  type DocumentAnalysisJob,
} from "@/lib/document-analysis-api";
import { cn } from "@/lib/utils";
import { AnalysisStatus } from "@/components/document-analysis/analysis-status";
import { DocumentPreview } from "@/components/document-analysis/document-preview";
import { DocumentUploadPanel } from "@/components/document-analysis/document-upload-panel";
import { useCurrentUser } from "@/components/workspace/current-user-context";

import { AutoEntryReviewPanel } from "./auto-entry-review-panel";

type WorkbenchPane = "file" | "preview" | "result";

const mobilePanes = [
  { id: "file", label: "File", icon: PanelLeft },
  { id: "preview", label: "Preview", icon: ScanLine },
  { id: "result", label: "Result", icon: FileUp },
] as const;

function fileMetadata(file: File): AnalyzableFile {
  return { name: file.name, size: file.size, type: file.type };
}

function analysisQueryFromLocation(): string | null {
  if (typeof window === "undefined") return null;
  return new URLSearchParams(window.location.search).get("analysis");
}

function replaceAnalysisQuery(analysisId: string | null) {
  const query = analysisId ? `?analysis=${encodeURIComponent(analysisId)}` : "";
  window.history.replaceState(null, "", `/content-understanding/auto-entry${query}`);
}

function analysisErrorMessage(cause: unknown, fallback: string): string {
  return cause instanceof DocumentAnalysisApiError ? cause.message : fallback;
}

export function AutoEntryWorkbench() {
  const currentUser = useCurrentUser();
  const available = canUseContentUnderstanding(currentUser);
  const [state, dispatch] = useReducer(documentAnalysisReducer, initialDocumentAnalysisState);
  const [browserFile, setBrowserFile] = useState<File | null>(null);
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [serverPreviewUrl, setServerPreviewUrl] = useState<string | null>(null);
  const [recentAnalyses, setRecentAnalyses] = useState<DocumentAnalysisJob[]>([]);
  const [recentLoading, setRecentLoading] = useState(false);
  const [review, setReview] = useState<AutoEntryReviewResponse | null>(null);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [activePane, setActivePane] = useState<WorkbenchPane>("file");
  const desktopInputRef = useRef<HTMLInputElement>(null);
  const mobileInputRef = useRef<HTMLInputElement>(null);
  const objectUrlRef = useRef<string | null>(null);

  useEffect(() => () => {
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
  }, []);

  const refreshRecentAnalyses = useCallback((signal?: AbortSignal) => {
    setRecentLoading(true);
    listDocumentAnalyses("CONTENT_UNDERSTANDING", 0, 10, signal, "AUTO_ENTRY")
      .then((page) => { if (!signal?.aborted) setRecentAnalyses(page.content); })
      .catch(() => { if (!signal?.aborted) setRecentAnalyses([]); })
      .finally(() => { if (!signal?.aborted) setRecentLoading(false); });
  }, []);

  const replaceBrowserFile = useCallback((file: File | null) => {
    if (objectUrlRef.current) URL.revokeObjectURL(objectUrlRef.current);
    objectUrlRef.current = file ? URL.createObjectURL(file) : null;
    setObjectUrl(objectUrlRef.current);
  }, []);

  const loadSucceededReview = useCallback(async (job: DocumentAnalysisJob, signal?: AbortSignal) => {
    setReviewLoading(true);
    try {
      const nextReview = await getAutoEntryReview(job.id, signal);
      if (signal?.aborted) return;
      setReview(nextReview);
      dispatch({
        type: "view",
        job,
        result: {
          analysisId: job.id,
          provider: job.provider,
          modelId: job.modelId,
          providerApiVersion: job.providerApiVersion,
          markdown: "",
          paragraphs: [],
          tables: [],
        },
      });
      setActivePane("result");
    } catch (cause) {
      if (!signal?.aborted) {
        dispatch({ type: "fail", job, message: analysisErrorMessage(cause, "自動入力結果を取得できませんでした。") });
      }
    } finally {
      if (!signal?.aborted) setReviewLoading(false);
    }
  }, []);

  const applyJob = useCallback((job: DocumentAnalysisJob, signal?: AbortSignal, preserveBrowserFile = false) => {
    if (job.provider !== "CONTENT_UNDERSTANDING" || job.profile !== "AUTO_ENTRY") {
      setServerPreviewUrl(null);
      setReview(null);
      dispatch({ type: "fail", message: "指定された分析は自動入力では表示できません。" });
      return;
    }
    if (!preserveBrowserFile) {
      setBrowserFile(null);
      replaceBrowserFile(null);
    }
    setServerPreviewUrl(documentAnalysisSourceUrl(job.id, "AUTO_ENTRY"));
    setReview(null);
    if (job.status === "SUCCEEDED") {
      void loadSucceededReview(job, signal);
      return;
    }
    dispatch({ type: "job", job });
  }, [loadSucceededReview, replaceBrowserFile]);

  const loadAnalysis = useCallback(async (analysisId: string, signal?: AbortSignal) => {
    setReviewLoading(false);
    setReview(null);
    setServerPreviewUrl(null);
    try {
      applyJob(await getDocumentAnalysis(analysisId, signal, "AUTO_ENTRY"), signal);
    } catch (cause) {
      if (!signal?.aborted) dispatch({ type: "fail", message: analysisErrorMessage(cause, "分析状態を取得できませんでした。") });
    }
  }, [applyJob]);

  useEffect(() => {
    if (!available) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => {
      refreshRecentAnalyses(controller.signal);
      const analysisId = analysisQueryFromLocation();
      if (analysisId) void loadAnalysis(analysisId, controller.signal);
    }, 0);
    return () => { controller.abort(); window.clearTimeout(timeout); };
  }, [available, loadAnalysis, refreshRecentAnalyses]);

  useEffect(() => {
    if (state.status !== "queued" && state.status !== "running") return;
    if (!state.job) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(async () => {
      try {
        applyJob(await getDocumentAnalysis(state.job!.id, controller.signal, "AUTO_ENTRY"), controller.signal, true);
      } catch (cause) {
        if (!controller.signal.aborted) dispatch({ type: "fail", job: state.job!, message: analysisErrorMessage(cause, "分析状態を取得できませんでした。") });
      }
    }, 1_000);
    return () => { controller.abort(); window.clearTimeout(timeout); };
  }, [applyJob, state.job, state.status]);

  function selectFiles(files: FileList) {
    const validation = validateSingleDocumentSelection(files);
    if (!validation.valid) {
      setBrowserFile(null); replaceBrowserFile(null); setServerPreviewUrl(null); setReview(null); setReviewLoading(false);
      replaceAnalysisQuery(null); dispatch({ type: "reject", message: validation.message }); setActivePane("file"); return;
    }
    const file = files[0];
    setBrowserFile(file); replaceBrowserFile(file); setServerPreviewUrl(null); setReview(null); setReviewLoading(false);
    replaceAnalysisQuery(null); dispatch({ type: "select", file: fileMetadata(file), validation }); setActivePane("preview");
  }

  function clearSelection() {
    setBrowserFile(null); replaceBrowserFile(null); setServerPreviewUrl(null); setReview(null); setReviewLoading(false);
    replaceAnalysisQuery(null); dispatch({ type: "clear" }); setActivePane("file");
  }

  const canRunAnalysis = browserFile !== null && !isDocumentAnalysisProcessing(state.status);
  const analysisFile = canRunAnalysis ? browserFile : null;

  async function runAnalysis() {
    if (!canRunAnalysis || analysisFile === null) return;
    dispatch({ type: "upload" }); setReview(null); setReviewLoading(false);
    try {
      const job = await createDocumentAnalysis("CONTENT_UNDERSTANDING", analysisFile, undefined, "AUTO_ENTRY");
      replaceAnalysisQuery(job.id); setServerPreviewUrl(documentAnalysisSourceUrl(job.id, "AUTO_ENTRY")); dispatch({ type: "job", job });
      refreshRecentAnalyses(); setActivePane("result");
    } catch (cause) {
      dispatch({ type: "fail", message: analysisErrorMessage(cause, "分析要求を開始できませんでした。") });
    }
  }

  function selectRecentAnalysis(job: DocumentAnalysisJob) {
    replaceAnalysisQuery(job.id); setBrowserFile(null); replaceBrowserFile(null); setReview(null); setServerPreviewUrl(null); setActivePane("result"); void loadAnalysis(job.id);
  }

  if (!available) return <main className="p-4 md:p-8"><div className="mx-auto max-w-3xl rounded-md border bg-card p-6 text-card-foreground"><h1 className="text-lg font-semibold">自動入力</h1><p className="mt-2 text-sm text-muted-foreground">この機能は現在利用できません。</p></div></main>;

  const toolbar = <div className="flex min-w-0 flex-wrap items-center justify-between gap-3 border-b bg-background px-4 py-3"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h1 className="truncate text-xl font-semibold">自動入力</h1><Badge variant="secondary">AUTO_ENTRY</Badge>{state.job ? <Badge variant="outline">Model: {state.job.modelId}</Badge> : null}</div><p className="mt-1 text-sm text-muted-foreground">帳票から抽出した値を確認する読み取り専用画面です。</p></div><Button disabled={!canRunAnalysis} onClick={() => void runAnalysis()} type="button">分析を実行</Button></div>;
  const filePaneProps = {
    onClear: clearSelection,
    onSelectFiles: selectFiles,
    onSelectRecentAnalysis: selectRecentAnalysis,
    recentAnalyses,
    recentLoading,
    selectedFile: state.selectedFile,
    selectionError: state.status === "failed" ? state.error : null,
  };
  const desktopFilePane = <DocumentUploadPanel {...filePaneProps} inputId="auto-entry-file-desktop" inputRef={desktopInputRef} />;
  const mobileFilePane = <DocumentUploadPanel {...filePaneProps} inputId="auto-entry-file-mobile" inputRef={mobileInputRef} />;
  const resultPane = <div className="flex h-full min-h-0 flex-col"><div className="border-b p-4"><AnalysisStatus state={state} viewLoading={reviewLoading} /></div><div className="min-h-0 flex-1"><AutoEntryReviewPanel review={review} /></div></div>;
  const previewFile = state.selectedFile ?? (state.job ? {
    name: state.job.originalFileName,
    size: state.job.fileSize,
    type: state.job.contentType,
  } : null);

  return <main className="p-4 md:p-8"><div className="mx-auto flex h-[calc(100svh-6rem)] max-w-[96rem] min-w-0 flex-col overflow-hidden rounded-md border bg-card text-card-foreground md:h-[calc(100svh-8rem)]">{toolbar}<div className="hidden min-h-0 min-w-0 flex-1 grid-cols-[16rem_minmax(0,1fr)_30rem] divide-x lg:grid">{desktopFilePane}<DocumentPreview file={previewFile} objectUrl={objectUrl} serverUrl={serverPreviewUrl} />{resultPane}</div><div className="flex min-h-0 min-w-0 flex-1 flex-col lg:hidden"><div aria-label="ワークベンチ表示切替" className="grid grid-cols-3 gap-1 border-b p-2" role="tablist">{mobilePanes.map((pane) => { const Icon = pane.icon; const selected = activePane === pane.id; return <Button aria-selected={selected} className={cn("justify-center", selected && "bg-muted text-foreground")} key={pane.id} onClick={() => setActivePane(pane.id)} role="tab" type="button" variant="ghost"><Icon data-icon="inline-start" />{pane.label}</Button>; })}</div><div className="min-h-0 flex-1 overflow-hidden" role="tabpanel">{activePane === "file" ? mobileFilePane : null}{activePane === "preview" ? <DocumentPreview file={previewFile} objectUrl={objectUrl} serverUrl={serverPreviewUrl} /> : null}{activePane === "result" ? resultPane : null}</div></div></div></main>;
}
