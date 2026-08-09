"use client";

import { useState } from "react";
import { Check, Copy } from "lucide-react";

import { Button } from "@/components/ui/button";

export function MarkdownResult({ markdown }: { markdown: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    await navigator.clipboard.writeText(markdown);
    setCopied(true);
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex justify-end">
        <Button aria-label="Markdownをコピー" onClick={copy} size="sm" type="button" variant="outline">
          {copied ? <Check data-icon="inline-start" /> : <Copy data-icon="inline-start" />}
          Copy
        </Button>
      </div>
      <pre className="min-h-0 flex-1 overflow-auto whitespace-pre-wrap rounded-md border bg-muted/30 p-3 text-xs leading-6">
        {markdown}
      </pre>
    </div>
  );
}

