"use client";

export function RawResult({ value }: { value: Record<string, unknown> }) {
  return (
    <pre className="min-h-0 flex-1 overflow-auto whitespace-pre-wrap rounded-md border bg-muted/30 p-3 text-xs leading-6">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

