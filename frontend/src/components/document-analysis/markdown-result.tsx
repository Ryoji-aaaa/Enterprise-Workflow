export function MarkdownResult({ markdown }: { markdown: string }) {
  return (
    <pre className="min-h-0 flex-1 overflow-auto whitespace-pre-wrap rounded-md border bg-muted/30 p-3 text-xs leading-6" data-testid="document-analysis-markdown-content">
      {markdown}
    </pre>
  );
}
