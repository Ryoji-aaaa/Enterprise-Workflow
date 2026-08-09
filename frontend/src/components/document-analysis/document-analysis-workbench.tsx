"use client";

import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import { FileUp, PanelLeft, ScanLine } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  canUseContentUnderstanding,
  canUseDocumentIntelligence,
} from "@/lib/backend-api";
import {
  type AnalyzableFile,
  type DocumentAnalysisProviderConfig,
  documentAnalysisReducer,
  isDocumentAnalysisProcessing,
  initialDocumentAnalysisState,
  mapDocumentAnalysisViewV1,
  validateSingleDocumentSelection,
} from "@/lib/document-analysis";
import {
  createDocumentAnalysis,
  documentAnalysisSourceUrl,
  DocumentAnalysisApiError,
  getDocumentAnalysis,
  getDocumentAnalysisRawResult,
  getDocumentAnalysisView,
  listDocumentAnalyses,
  type DocumentAnalysisJob,
  type DocumentAnalysisRawResult,
} from "@/lib/document-analysis-api";
import { cn } from "@/lib/utils";

import { AnalysisResultTabs } from "./analysis-result-tabs";
import { AnalysisStatus } from "./analysis-status";
import { DocumentAnalysisToolbar } from "./document-analysis-toolbar";
import { DocumentPreview } from "./document-preview";
import { DocumentUploadPanel } from "./document-upload-panel";
import { useCurrentUser } from "../workspace/current-user-context";

type WorkbenchPane = "file" | "preview" | "result";

const mobilePanes: Array<{ id: WorkbenchPane; label: string; icon: typeof FileUp }> = [
  { id: "file", label: "File", icon: PanelLeft },
  { id: "preview", label: "Preview", icon: ScanLine },
  { id: "result", label: "Result", icon: FileUp },
];

type RawResultState =
  | { status: "idle"; analysisId: string | null; value: null; error: null }
  | { status: "loading"; analysisId: string; value: null; error: null }
  | { status: "success"; analysisId: string; value: DocumentAnalysisRawResult; error: null }
  | { status: "error"; analysisId: string; value: null; error: string };

const initialRawResultState: RawResultState = {
  status: "idle",
  analysisId: null,
  value: null,
  error: null,
};

function fileMetadata(file: File): AnalyzableFile {
  return { name: file.name, size: file.size, type: file.type };
}

function analysisQueryFromLocation(): string | null {
  if (typeof window === "undefined") return null;
  return new URLSearchParams(window.location.search).get("analysis");
}

function replaceAnalysisQuery(route: string, analysisId: string | null) {
  const query = analysisId ? `?analysis=${encodeURIComponent(analysisId)}` : "";
  window.history.replaceState(null, "", `${route}${query}`);
}

function analysisErrorMessage(cause: unknown, fallback: string): string {
  if (cause instanceof DocumentAnalysisApiError) return cause.message;
  return fallback;
}

export function DocumentAnalysisWorkbench({
  config,
}: {
  config: DocumentAnalysisProviderConfig;
}) {
  const currentUser = useCurrentUser();
  const available = config.provider === "DOCUMENT_INTELLIGENCE"
    ? canUseDocumentIntelligence(currentUser)
    : canUseContentUnderstanding(currentUser);
  const [state, dispatch] = useReducer(documentAnalysisReducer, initialDocumentAnalysisState);
  const [browserFile, setBrowserFile] = useState<File | null>(null);
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [recentAnalyses, setRecentAnalyses] = useState<DocumentAnalysisJob[]>([]);
  const [recentLoading, setRecentLoading] = useState(false);
  const [serverPreviewUrl, setServerPreviewUrl] = useState<string | null>(null);
  const [viewLoading, setViewLoading] = useState(false);
  const [rawState, setRawState] = useState<RawResultState>(initialRawResultState);
  const [activePane, setActivePane] = useState<WorkbenchPane>("file");
  const desktopInputRef = useRef<HTMLInputElement>(null);
  const mobileInputRef = useRef<HTMLInputElement>(null);
  const objectUrlRef = useRef<string | null>(null);

  useEffect(() => {
    return () => {
      if (objectUrlRef.current) {
        URL.revokeObjectURL(objectUrlRef.current);
      }
    };
  }, []);

  const refreshRecentAnalyses = useCallback((signal?: AbortSignal) => {
    setRecentLoading(true);
    listDocumentAnalyses(config.provider, 0, 10, signal).then((page) => {
      if (!signal?.aborted) {
        setRecentAnalyses(page.content);
      }
    }).catch(() => {
      if (!signal?.aborted) {
        setRecentAnalyses([]);
      }
    }).finally(() => {
      if (!signal?.aborted) {
        setRecentLoading(false);
      }
    });
  }, [config.provider]);

  const replaceBrowserFile = useCallback((file: File | null) => {
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }

    if (!file) {
      setObjectUrl(null);
      return;
    }

    const nextObjectUrl = URL.createObjectURL(file);
    objectUrlRef.current = nextObjectUrl;
    setObjectUrl(nextObjectUrl);
  }, []);

  const loadSucceededView = useCallback(async (job: DocumentAnalysisJob, signal?: AbortSignal) => {
    setViewLoading(true);
    try {
      const view = await getDocumentAnalysisView(job.id, signal);
      if (signal?.aborted) return;
      if (view.schemaVersion !== 1) {
        dispatch({ type: "fail", message: "対応していない分析結果形式です。", job });
        return;
      }
      dispatch({ type: "view", job, result: mapDocumentAnalysisViewV1(view) });
      setActivePane("result");
    } catch (cause) {
      if (!signal?.aborted) {
        dispatch({
          type: "fail",
          message: analysisErrorMessage(cause, "分析結果を取得できませんでした。"),
          job,
        });
      }
    } finally {
      if (!signal?.aborted) setViewLoading(false);
    }
  }, []);

  const applyJob = useCallback((
    job: DocumentAnalysisJob,
    signal?: AbortSignal,
    options: { preserveBrowserFile?: boolean } = {},
  ) => {
    if (job.provider !== config.provider) {
      dispatch({
        type: "fail",
        message: "指定された分析はこの機能では表示できません。",
      });
      setServerPreviewUrl(null);
      return;
    }
    if (!options.preserveBrowserFile) {
      setBrowserFile(null);
      replaceBrowserFile(null);
    }
    setServerPreviewUrl(documentAnalysisSourceUrl(job.id));
    setRawState({ ...initialRawResultState, analysisId: job.id });
    if (job.status === "SUCCEEDED") {
      void loadSucceededView(job, signal);
      return;
    }
    dispatch({ type: "job", job });
  }, [config.provider, loadSucceededView, replaceBrowserFile]);

  const loadAnalysis = useCallback(async (analysisId: string, signal?: AbortSignal) => {
    setViewLoading(false);
    try {
      applyJob(await getDocumentAnalysis(analysisId, signal), signal);
    } catch (cause) {
      if (!signal?.aborted) {
        dispatch({
          type: "fail",
          message: analysisErrorMessage(cause, "分析状態を取得できませんでした。"),
        });
      }
    }
  }, [applyJob]);

  useEffect(() => {
    if (!available) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => {
      refreshRecentAnalyses(controller.signal);
      const analysisId = analysisQueryFromLocation();
      if (analysisId) {
        void loadAnalysis(analysisId, controller.signal);
      }
    }, 0);
    return () => {
      controller.abort();
      window.clearTimeout(timeout);
    };
  }, [available, config.provider, loadAnalysis, refreshRecentAnalyses]);

  useEffect(() => {
    if (state.status !== "queued" && state.status !== "running") return;
    const job = state.job;
    if (!job) return;
    const controller = new AbortController();
    const timeout = window.setTimeout(async () => {
      try {
        applyJob(
          await getDocumentAnalysis(job.id, controller.signal),
          controller.signal,
          { preserveBrowserFile: true },
        );
      } catch (cause) {
        if (!controller.signal.aborted) {
          dispatch({
            type: "fail",
            message: analysisErrorMessage(cause, "分析状態を取得できませんでした。"),
            job,
          });
        }
      }
    }, 1_000);
    return () => {
      controller.abort();
      window.clearTimeout(timeout);
    };
  }, [applyJob, state.job, state.status]);

  function selectFiles(files: FileList) {
    const validation = validateSingleDocumentSelection(files);
    if (!validation.valid) {
      replaceBrowserFile(null);
      setBrowserFile(null);
      setServerPreviewUrl(null);
      setRawState(initialRawResultState);
      setViewLoading(false);
      replaceAnalysisQuery(config.route, null);
      dispatch({ type: "reject", message: validation.message });
      setActivePane("file");
      return;
    }

    const file = files[0];
    setBrowserFile(file);
    replaceBrowserFile(file);
    setServerPreviewUrl(null);
    setRawState(initialRawResultState);
    setViewLoading(false);
    replaceAnalysisQuery(config.route, null);
    dispatch({ type: "select", file: fileMetadata(file), validation });
    setActivePane("preview");
  }

  function clearSelection() {
    setBrowserFile(null);
    replaceBrowserFile(null);
    setServerPreviewUrl(null);
    setRawState(initialRawResultState);
    setViewLoading(false);
    replaceAnalysisQuery(config.route, null);
    dispatch({ type: "clear" });
    setActivePane("file");
  }

  async function runAnalysis() {
    if (!browserFile || isDocumentAnalysisProcessing(state.status)) {
      return;
    }

    const controller = new AbortController();
    dispatch({ type: "upload" });
    setRawState(initialRawResultState);
    setViewLoading(false);
    try {
      const job = await createDocumentAnalysis(config.provider, browserFile, controller.signal);
      replaceAnalysisQuery(config.route, job.id);
      setServerPreviewUrl(documentAnalysisSourceUrl(job.id));
      dispatch({ type: "job", job });
      refreshRecentAnalyses();
      setActivePane("result");
    } catch (cause) {
      dispatch({
        type: "fail",
        message: analysisErrorMessage(cause, "分析要求を開始できませんでした。"),
      });
    }
  }

  function selectRecentAnalysis(job: DocumentAnalysisJob) {
    replaceAnalysisQuery(config.route, job.id);
    setBrowserFile(null);
    replaceBrowserFile(null);
    setRawState({ ...initialRawResultState, analysisId: job.id });
    setActivePane("result");
    void loadAnalysis(job.id);
  }

  async function loadRawResult() {
    const job = state.job;
    if (!job || rawState.status === "loading") return;
    if (rawState.status === "success" && rawState.analysisId === job.id) return;

    setRawState({ status: "loading", analysisId: job.id, value: null, error: null });
    try {
      setRawState({
        status: "success",
        analysisId: job.id,
        value: await getDocumentAnalysisRawResult(job.id),
        error: null,
      });
    } catch (cause) {
      setRawState({
        status: "error",
        analysisId: job.id,
        value: null,
        error: analysisErrorMessage(cause, "Raw Resultを取得できませんでした。"),
      });
    }
  }

  if (!available) {
    return (
      <main className="p-4 md:p-8">
        <div className="mx-auto max-w-3xl rounded-md border bg-card p-6 text-card-foreground">
          <h1 className="text-lg font-semibold">{config.title}</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            この機能は現在利用できません。
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="p-4 md:p-8">
      <div className="mx-auto flex h-[calc(100svh-6rem)] max-w-[96rem] min-w-0 flex-col overflow-hidden rounded-md border bg-card text-card-foreground md:h-[calc(100svh-8rem)]">
        <DocumentAnalysisToolbar
          config={config}
          hasValidFile={Boolean(state.selectedFile)}
          job={state.job}
          onRun={() => void runAnalysis()}
          status={state.status}
        />

        <div className="hidden min-h-0 min-w-0 flex-1 grid-cols-[16rem_minmax(0,1fr)_26rem] divide-x lg:grid">
          <DocumentUploadPanel
            inputId="document-analysis-file-desktop"
            inputRef={desktopInputRef}
            onClear={clearSelection}
            onSelectRecentAnalysis={selectRecentAnalysis}
            onSelectFiles={selectFiles}
            recentAnalyses={recentAnalyses}
            recentLoading={recentLoading}
            selectedFile={state.selectedFile}
            selectionError={state.status === "failed" ? state.error : null}
          />
          <div className="min-h-0 min-w-0">
            <DocumentPreview
              file={state.selectedFile}
              objectUrl={objectUrl}
              serverUrl={serverPreviewUrl}
            />
          </div>
          <div className="flex min-h-0 min-w-0 flex-col">
            <div className="border-b p-4">
              <AnalysisStatus state={state} viewLoading={viewLoading} />
            </div>
            <div className="min-h-0 flex-1">
              <AnalysisResultTabs
                job={state.job}
                onLoadRawResult={() => void loadRawResult()}
                rawState={rawState}
                result={state.result}
              />
            </div>
          </div>
        </div>

        <div className="flex min-h-0 min-w-0 flex-1 flex-col lg:hidden">
          <div aria-label="ワークベンチ表示切替" className="grid grid-cols-3 gap-1 border-b p-2" role="tablist">
            {mobilePanes.map((pane) => {
              const Icon = pane.icon;
              const selected = activePane === pane.id;
              return (
                <Button
                  aria-selected={selected}
                  className={cn("justify-center", selected && "bg-muted text-foreground")}
                  key={pane.id}
                  onClick={() => setActivePane(pane.id)}
                  role="tab"
                  type="button"
                  variant="ghost"
                >
                  <Icon data-icon="inline-start" />
                  {pane.label}
                </Button>
              );
            })}
          </div>
          <div className="min-h-0 flex-1 overflow-hidden" role="tabpanel">
            {activePane === "file" && (
              <DocumentUploadPanel
                inputId="document-analysis-file-mobile"
                inputRef={mobileInputRef}
                onClear={clearSelection}
                onSelectRecentAnalysis={selectRecentAnalysis}
                onSelectFiles={selectFiles}
                recentAnalyses={recentAnalyses}
                recentLoading={recentLoading}
                selectedFile={state.selectedFile}
                selectionError={state.status === "failed" ? state.error : null}
              />
            )}
            {activePane === "preview" && (
              <DocumentPreview
                file={state.selectedFile}
                objectUrl={objectUrl}
                serverUrl={serverPreviewUrl}
              />
            )}
            {activePane === "result" && (
              <div className="flex h-full min-h-0 flex-col">
                <div className="border-b p-4">
                  <AnalysisStatus state={state} viewLoading={viewLoading} />
                </div>
                <div className="min-h-0 flex-1">
                  <AnalysisResultTabs
                    job={state.job}
                    onLoadRawResult={() => void loadRawResult()}
                    rawState={rawState}
                    result={state.result}
                  />
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </main>
  );
}
