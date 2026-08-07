"use client";

import { useEffect, useReducer, useRef, useState } from "react";
import { FileUp, PanelLeft, ScanLine } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  type AnalyzableFile,
  type DocumentAnalysisProviderConfig,
  documentAnalysisReducer,
  initialDocumentAnalysisState,
  validateSingleDocumentSelection,
} from "@/lib/document-analysis";
import { documentAnalysisFixture } from "@/lib/document-analysis-fixtures";
import { cn } from "@/lib/utils";

import { AnalysisResultTabs } from "./analysis-result-tabs";
import { AnalysisStatus } from "./analysis-status";
import { DocumentAnalysisToolbar } from "./document-analysis-toolbar";
import { DocumentPreview } from "./document-preview";
import { DocumentUploadPanel } from "./document-upload-panel";

type WorkbenchPane = "file" | "preview" | "result";

const mobilePanes: Array<{ id: WorkbenchPane; label: string; icon: typeof FileUp }> = [
  { id: "file", label: "File", icon: PanelLeft },
  { id: "preview", label: "Preview", icon: ScanLine },
  { id: "result", label: "Result", icon: FileUp },
];

function fileMetadata(file: File): AnalyzableFile {
  return { name: file.name, size: file.size, type: file.type };
}

export function DocumentAnalysisWorkbench({
  config,
}: {
  config: DocumentAnalysisProviderConfig;
}) {
  const [state, dispatch] = useReducer(documentAnalysisReducer, initialDocumentAnalysisState);
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [recentAnalyses, setRecentAnalyses] = useState<string[]>([]);
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

  function replaceBrowserFile(file: File | null) {
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
  }

  function selectFiles(files: FileList) {
    const validation = validateSingleDocumentSelection(files);
    if (!validation.valid) {
      replaceBrowserFile(null);
      dispatch({ type: "reject", message: validation.message });
      setActivePane("file");
      return;
    }

    const file = files[0];
    replaceBrowserFile(file);
    dispatch({ type: "select", file: fileMetadata(file), validation });
    setActivePane("preview");
  }

  function clearSelection() {
    replaceBrowserFile(null);
    dispatch({ type: "clear" });
    setActivePane("file");
  }

  function runFixtureAnalysis() {
    const selectedFile = state.selectedFile;
    if (!selectedFile) {
      return;
    }

    dispatch({ type: "runFixture", result: documentAnalysisFixture(config.provider) });
    setRecentAnalyses((items) => [
      selectedFile.name,
      ...items.filter((item) => item !== selectedFile.name),
    ].slice(0, 5));
    setActivePane("result");
  }

  return (
    <main className="p-4 md:p-8">
      <div className="mx-auto flex h-[calc(100svh-6rem)] max-w-[96rem] min-w-0 flex-col overflow-hidden rounded-md border bg-card text-card-foreground md:h-[calc(100svh-8rem)]">
        <DocumentAnalysisToolbar
          config={config}
          hasValidFile={Boolean(state.selectedFile)}
          onRun={runFixtureAnalysis}
          status={state.status}
        />

        <div className="hidden min-h-0 min-w-0 flex-1 grid-cols-[16rem_minmax(0,1fr)_26rem] divide-x lg:grid">
          <DocumentUploadPanel
            inputId="document-analysis-file-desktop"
            inputRef={desktopInputRef}
            onClear={clearSelection}
            onSelectFiles={selectFiles}
            recentAnalyses={recentAnalyses}
            selectedFile={state.selectedFile}
          />
          <div className="min-h-0 min-w-0">
            <DocumentPreview file={state.selectedFile} objectUrl={objectUrl} />
          </div>
          <div className="flex min-h-0 min-w-0 flex-col">
            <div className="border-b p-4">
              <AnalysisStatus state={state} />
            </div>
            <div className="min-h-0 flex-1">
              <AnalysisResultTabs result={state.result} />
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
                onSelectFiles={selectFiles}
                recentAnalyses={recentAnalyses}
                selectedFile={state.selectedFile}
              />
            )}
            {activePane === "preview" && <DocumentPreview file={state.selectedFile} objectUrl={objectUrl} />}
            {activePane === "result" && (
              <div className="flex h-full min-h-0 flex-col">
                <div className="border-b p-4">
                  <AnalysisStatus state={state} />
                </div>
                <div className="min-h-0 flex-1">
                  <AnalysisResultTabs result={state.result} />
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </main>
  );
}
