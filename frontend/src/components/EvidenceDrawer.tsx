import { ExternalLink } from "lucide-react";
import { useMemo } from "react";
import ReactMarkdown from "react-markdown";
import { Link } from "react-router-dom";
import remarkGfm from "remark-gfm";

import { useRunEvidence } from "@/api/hooks";
import { Badge } from "@/components/ui/badge";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDateTime } from "@/lib/format";

export interface EvidenceDrawerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  runId: string;
  evidenceIds: string[];
}

const SOURCE_TYPE_ICON: Record<string, string> = {
  pricing_page: "📄",
  local_note: "📝",
  unknown_source: "📦",
  g2_review: "⭐",
  reddit: "💬",
  hn_thread: "🟧",
};

function getSourceAuthority(metadata: Record<string, unknown> | null): string {
  const value = metadata?.source_authority;
  return typeof value === "string" && value ? value : "unknown";
}

function toAuthorityLabel(value: string): string {
  if (value === "official") {
    return "官方来源";
  }
  if (value === "third_party") {
    return "第三方来源";
  }
  return "来源未知";
}

function toLanguageLabel(language: string | null): string {
  if (!language) {
    return "语言未知";
  }
  if (language === "zh") {
    return "中文原文";
  }
  if (language === "en") {
    return "英文原文";
  }
  if (language === "ja") {
    return "日文原文";
  }
  if (language === "ko") {
    return "韩文原文";
  }
  return `${language.toUpperCase()} 原文`;
}

function metadataBoolean(
  metadata: Record<string, unknown> | null,
  key: string,
): boolean {
  const value = metadata?.[key];
  return value === true;
}

function metadataString(
  metadata: Record<string, unknown> | null,
  key: string,
): string | null {
  const value = metadata?.[key];
  return typeof value === "string" && value.trim().length > 0 ? value : null;
}

function evidenceFloorReasonLabel(
  metadata: Record<string, unknown> | null,
): string | null {
  const reason = metadataString(metadata, "evidence_floor_reason");
  if (reason === "source_blocklist") {
    return "来源受限";
  }
  if (reason === "low_semantic") {
    return "语义质量不足";
  }
  if (reason === "competitor_grounding_miss") {
    return "竞品归属不充分";
  }
  if (reason === "adjacent_below_target_floor") {
    return "目标品类证据不足";
  }
  return reason;
}

function isSummaryFragment(text: string): boolean {
  const compact = text.trim();
  if (!compact) {
    return true;
  }
  if (compact.endsWith("...") || compact.endsWith("…")) {
    return true;
  }
  return compact.length < 220;
}

export function EvidenceDrawer({
  open,
  onOpenChange,
  runId,
  evidenceIds,
}: EvidenceDrawerProps): JSX.Element {
  const shouldFetch = open && Boolean(runId);
  const evidenceQuery = useRunEvidence(
    runId,
    { evidence_ids: evidenceIds },
    {
      enabled: shouldFetch,
    },
  );

  const evidenceRows = useMemo(() => {
    const items = Array.isArray(evidenceQuery.data) ? evidenceQuery.data : [];
    const byId = new Map(
      items.map((item) => [item.evidence_id.trim().toLocaleLowerCase(), item]),
    );
    return evidenceIds
      .map((evidenceId) => byId.get(evidenceId.trim().toLocaleLowerCase()))
      .filter((item): item is NonNullable<typeof item> => Boolean(item));
  }, [evidenceIds, evidenceQuery.data]);

  return (
    <Sheet onOpenChange={onOpenChange} open={open}>
      <SheetContent className="w-full sm:max-w-xl">
        <SheetHeader>
          <SheetTitle>Evidence 引用</SheetTitle>
          <SheetDescription>run_id: {runId}</SheetDescription>
        </SheetHeader>

        <div className="mt-4 max-h-[calc(100vh-140px)] space-y-3 overflow-y-auto pr-1">
          {evidenceQuery.isLoading ? (
            <>
              <Skeleton className="h-24 w-full" />
              <Skeleton className="h-24 w-full" />
            </>
          ) : null}

          {evidenceQuery.isError ? (
            <div className="rounded-md border border-red-400/40 bg-red-500/10 p-3 text-sm text-red-200">
              {evidenceQuery.error.message}
            </div>
          ) : null}

          {!evidenceQuery.isLoading && !evidenceQuery.isError && evidenceRows.length === 0 ? (
            <div className="rounded-md border border-border bg-muted/20 p-3 text-sm text-muted-foreground">
              当前引用未找到对应 evidence 记录。
            </div>
          ) : null}

          {evidenceRows.map((item) => {
            const sourceIcon = SOURCE_TYPE_ICON[item.source_type] ?? "📌";
            const sourceAuthority = getSourceAuthority(item.metadata);
            const quoteText = item.quote.trim();
            const sanitizedText = item.sanitized_text.trim();
            // `quote` is often an LLM-sized excerpt. Prefer the collected
            // sanitized body when it is available so a citation opens with
            // concrete source content instead of only a one-line summary.
            const originalText =
              sanitizedText.length > 0 &&
              (quoteText.length === 0 || (isSummaryFragment(quoteText) && sanitizedText.length > quoteText.length))
                ? sanitizedText
                : quoteText || sanitizedText;
            const showQuoteSeparately =
              quoteText.length > 0 && originalText !== quoteText && quoteText !== sanitizedText;
            const renderedText =
              originalText.length > 16000
                ? `${originalText.slice(0, 16000)}\n\n> 正文较长，已先展示前 16,000 个字符；点击“查看完整证据”可继续查看。`
                : originalText;
            const isEvidenceFloor = metadataBoolean(item.metadata, "evidence_floor");
            const floorReason = evidenceFloorReasonLabel(item.metadata);
            const summaryFragment = !isEvidenceFloor && isSummaryFragment(originalText);
            const translatedExcerpt =
              typeof item.translated_excerpt === "string" && item.translated_excerpt.trim().length > 0
                ? item.translated_excerpt
                : null;
            return (
              <article className="space-y-2 rounded-md border border-border bg-card p-3" key={item.evidence_id}>
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <span>{sourceIcon}</span>
                  <span>{item.source_type}</span>
                  <span>·</span>
                  <span>{formatDateTime(item.collected_at)}</span>
                </div>
                {item.source_title ? (
                  <div className="text-sm font-medium text-foreground">{item.source_title}</div>
                ) : null}
                <div className="flex flex-wrap gap-1.5">
                  <Badge variant={sourceAuthority === "official" ? "success" : "secondary"}>
                    {toAuthorityLabel(sourceAuthority)}
                  </Badge>
                  <Badge variant="secondary">{toLanguageLabel(item.source_language)}</Badge>
                  <Badge variant={item.desensitized ? "success" : "warning"}>
                    {item.desensitized ? "已脱敏" : "未脱敏"}
                  </Badge>
                  {isEvidenceFloor ? <Badge variant="warning">兜底证据</Badge> : null}
                  {summaryFragment ? <Badge variant="secondary">摘要片段</Badge> : null}
                </div>
                {isEvidenceFloor && floorReason ? (
                  <p className="text-xs text-amber-200">兜底原因：{floorReason}</p>
                ) : null}
                <div className="space-y-3">
                  <div className="space-y-1">
                    <p className="text-xs text-muted-foreground">证据原文</p>
                    <div className="prose prose-invert max-w-none text-sm leading-6 prose-headings:mb-1.5 prose-headings:mt-3 prose-headings:text-sm prose-headings:font-semibold prose-headings:text-foreground prose-p:my-1.5 prose-p:text-foreground prose-strong:text-foreground prose-a:text-primary prose-li:my-0.5">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>{renderedText}</ReactMarkdown>
                    </div>
                  </div>
                  {showQuoteSeparately ? (
                    <div className="space-y-1 rounded-md border border-border/70 bg-muted/20 p-2">
                      <p className="text-xs text-muted-foreground">关键摘录</p>
                      <div className="prose prose-invert max-w-none text-sm leading-6 prose-p:my-1.5 prose-p:text-foreground">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{quoteText}</ReactMarkdown>
                      </div>
                    </div>
                  ) : null}
                  {translatedExcerpt ? (
                    <div className="space-y-1 rounded-md border border-primary/20 bg-primary/[0.06] p-2">
                      <p className="text-xs text-primary">报告语言摘要/译文</p>
                      <div className="prose prose-invert max-w-none text-sm leading-6 prose-headings:mb-1.5 prose-headings:mt-3 prose-headings:text-sm prose-headings:font-semibold prose-headings:text-foreground prose-p:my-1.5 prose-p:text-foreground prose-strong:text-foreground prose-a:text-primary prose-li:my-0.5">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{translatedExcerpt}</ReactMarkdown>
                      </div>
                    </div>
                  ) : null}
                </div>
                <div className="text-xs text-muted-foreground">
                  <span>competitor: {item.competitor_id ?? "-"}</span>
                </div>
                <div className="flex flex-wrap items-center gap-2 pt-1">
                  <Link
                    className="inline-flex rounded-md border border-border px-2 py-1 text-xs text-muted-foreground hover:border-primary hover:text-foreground"
                    onClick={() => onOpenChange(false)}
                    to={`/app/runs/${runId}/evidence?evidence_id=${encodeURIComponent(item.evidence_id)}`}
                  >
                    查看完整证据
                  </Link>
                  {item.source_url ? (
                    <a
                      className="inline-flex items-center gap-1 rounded-md border border-primary/40 px-2 py-1 text-xs text-primary hover:border-primary hover:bg-primary/[0.06]"
                      href={item.source_url}
                      rel="noreferrer"
                      target="_blank"
                    >
                      <ExternalLink className="h-3 w-3" />
                      打开原页面
                    </a>
                  ) : null}
                </div>
              </article>
            );
          })}
        </div>
      </SheetContent>
    </Sheet>
  );
}
