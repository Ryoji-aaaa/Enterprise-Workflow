"use client";

import { useState } from "react";
import { Check, Copy } from "lucide-react";

import { Button } from "@/components/ui/button";

export function CopyToClipboardButton({
  text,
  label,
  disabled = false,
}: {
  text: string;
  label: string;
  disabled?: boolean;
}) {
  const [copiedText, setCopiedText] = useState<string | null>(null);
  const [failedText, setFailedText] = useState<string | null>(null);
  const copied = copiedText === text;
  const copyFailed = failedText === text;

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedText(text);
      setFailedText(null);
    } catch {
      setCopiedText(null);
      setFailedText(text);
    }
  }

  return (
    <div className="flex items-center gap-2">
      {copyFailed && <span className="text-xs text-destructive" role="alert">コピーできませんでした。</span>}
      <Button aria-label={label} disabled={disabled} onClick={() => void copy()} size="sm" type="button" variant="outline">
        {copied ? <Check data-icon="inline-start" /> : <Copy data-icon="inline-start" />}
        Copy
      </Button>
    </div>
  );
}
