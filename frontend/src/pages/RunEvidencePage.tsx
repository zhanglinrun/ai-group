import type { LucideIcon } from "lucide-react";
import { CircleDollarSign, FileText, Globe, MessageSquareText, Sparkles } from "lucide-react";
import { useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";

import { useRunDetail, useRunEvidence } from "@/api/hooks";
import type { EvidenceListItemResponse } from "@/api/types";
import { RunBreadcrumb } from "@/components/RunBreadcrumb";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDateTime, formatRunTitle } from "@/lib/format";
import { cn } from "@/lib/utils";

interface SourceMeta {
  label: string;
  icon: LucideIcon;
}

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

function isEvidenceFloorRow(item: EvidenceListItemResponse): boolean {
  return metadataBoolean(item.metadata, "evidence_floor");
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

function toSourceMeta(sourceType: string): SourceMeta {
  const normalized = sourceType.toLowerCase();
  if (normalized.includes("pricing")) {
    return { label: "定价页", icon: CircleDollarSign };
  }
  if (normalized.includes("review")) {
    return { label: "用户评论", icon: MessageSquareText };
  }
  if (normalized.includes("snapshot")) {
    return { label: "网页快照", icon: FileText };
  }
  if (normalized.includes("article")) {
    return { label: "文章信息", icon: FileText };
  }
  return { label: sourceType, icon: Globe };
}

export function RunEvidencePage(): JSX.Element {
  const { runId: runIdFromParams } = useParams<{ runId: string }>();
  const runId = runIdFromParams ?? "";
  const [searchParams, setSearchParams] = useSearchParams();

  const competitorId = searchParams.get("competitor_id")?.trim() ?? "";
  const sourceType = searchParams.get("source_type")?.trim() ?? "";
  const sourceAuthority = searchParams.get("source_authority")?.trim() ?? "";
  const highlightedEvidenceId = searchParams.get("evidence_id")?.trim() ?? "";
  const [showOnlyQualifiedEvidence, setShowOnlyQualifiedEvidence] =
    useState<boolean>(true);

  const detailQuery = useRunDetail(runId);
  const allEvidenceQuery = useRunEvidence(runId, {}, { enabled: Boolean(runId) });
  const filteredEvidenceQuery = useRunEvidence(
    runId,
    {
      competitor_id: competitorId || undefined,
      source_type: sourceType || undefined,
    },
    { enabled: Boolean(runId) },
  );

  const sourceTypeOptions = useMemo(() => {
    const values = new Set<string>();
    for (const item of allEvidenceQuery.data ?? []) {
      values.add(item.source_type);
    }
    return Array.from(values).sort();
  }, [allEvidenceQuery.data]);

  const sourceAuthorityOptions = useMemo(() => {
    const values = new Set<string>();
    for (const item of allEvidenceQuery.data ?? []) {
      values.add(getSourceAuthority(item.metadata));
    }
    return Array.from(values).sort();
  }, [allEvidenceQuery.data]);

  const visibleEvidence = useMemo(() => {
    let items = filteredEvidenceQuery.data ?? [];
    if (sourceAuthority) {
      items = items.filter((item) => getSourceAuthority(item.metadata) === sourceAuthority);
    }
    if (showOnlyQualifiedEvidence) {
      items = items.filter((item) => !isEvidenceFloorRow(item));
    }
    return items;
  }, [filteredEvidenceQuery.data, showOnlyQualifiedEvidence, sourceAuthority]);

  const hiddenFloorCount = useMemo(() => {
    if (!showOnlyQualifiedEvidence) {
      return 0;
    }
    let items = filteredEvidenceQuery.data ?? [];
    if (sourceAuthority) {
      items = items.filter((item) => getSourceAuthority(item.metadata) === sourceAuthority);
    }
    return items.filter((item) => isEvidenceFloorRow(item)).length;
  }, [filteredEvidenceQuery.data, showOnlyQualifiedEvidence, sourceAuthority]);

  const hasHighlightedEvidence = useMemo(() => {
    if (!highlightedEvidenceId) {
      return false;
    }
    return visibleEvidence.some(
      (item) => item.evidence_id === highlightedEvidenceId,
    );
  }, [highlightedEvidenceId, visibleEvidence]);
  const groupedEvidence = useMemo(() => {
    const groups = new Map<string, EvidenceListItemResponse[]>();
    for (const item of visibleEvidence) {
      const groupKey = item.competitor_id ?? "未标注竞品";
      const current = groups.get(groupKey) ?? [];
      current.push(item);
      groups.set(groupKey, current);
    }
    return Array.from(groups.entries()).sort(([left], [right]) =>
      left.localeCompare(right, "zh-CN"),
    );
  }, [visibleEvidence]);

  function patchSearchParams(next: {
    competitorId?: string;
    sourceType?: string;
    sourceAuthority?: string;
    highlightedEvidenceId?: string;
  }): void {
    const params = new URLSearchParams(searchParams);
    if (next.competitorId !== undefined) {
      if (next.competitorId) {
        params.set("competitor_id", next.competitorId);
      } else {
        params.delete("competitor_id");
      }
    }
    if (next.sourceType !== undefined) {
      if (next.sourceType) {
        params.set("source_type", next.sourceType);
      } else {
        params.delete("source_type");
      }
    }
    if (next.sourceAuthority !== undefined) {
      if (next.sourceAuthority) {
        params.set("source_authority", next.sourceAuthority);
      } else {
        params.delete("source_authority");
      }
    }
    if (next.highlightedEvidenceId !== undefined) {
      if (next.highlightedEvidenceId) {
        params.set("evidence_id", next.highlightedEvidenceId);
      } else {
        params.delete("evidence_id");
      }
    }
    setSearchParams(params, { replace: true });
  }

  function clearFilters(): void {
    setSearchParams(new URLSearchParams(), { replace: true });
    setShowOnlyQualifiedEvidence(true);
  }

  return (
    <section className="space-y-5">
      <header className="space-y-3">
        <RunBreadcrumb run={detailQuery.data} current="证据库" />
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="inline-flex items-center gap-2 text-xs text-primary">
              <Sparkles className="h-3.5 w-3.5" />
              可追溯证据库
            </p>
            <h1
              className="truncate text-2xl font-semibold"
              title={detailQuery.data?.user_query}
            >
              {detailQuery.data ? formatRunTitle(detailQuery.data, { max: 60 }) : "证据库"}
            </h1>
            <p className="text-xs text-muted-foreground">run_id: {runId}</p>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-2">
            <Link
              className="rounded-md border border-border px-3 py-1.5 text-sm text-muted-foreground hover:border-primary hover:text-foreground"
              to={`/app/runs/${runId}/trace`}
            >
              查看 Trace
            </Link>
          </div>
        </div>
      </header>

      <div className="grid gap-3 sm:grid-cols-3">
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-muted-foreground">当前筛选结果</p>
            <p className="mt-1 text-2xl font-semibold tabular-nums">
              {visibleEvidence.length}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-muted-foreground">总证据量</p>
            <p className="mt-1 text-2xl font-semibold tabular-nums">{(allEvidenceQuery.data ?? []).length}</p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-muted-foreground">已分组竞品</p>
            <p className="mt-1 text-2xl font-semibold tabular-nums">{groupedEvidence.length}</p>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">筛选</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-3 sm:grid-cols-5">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">competitor</span>
            <NativeSelect
              onChange={(event) =>
                patchSearchParams({
                  competitorId: event.currentTarget.value,
                  highlightedEvidenceId: "",
                })
              }
              value={competitorId}
            >
              <option value="">全部</option>
              {(detailQuery.data?.competitors ?? []).map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </NativeSelect>
          </label>

          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">source_type</span>
            <NativeSelect
              onChange={(event) =>
                patchSearchParams({
                  sourceType: event.currentTarget.value,
                  highlightedEvidenceId: "",
                })
              }
              value={sourceType}
            >
              <option value="">全部</option>
              {sourceTypeOptions.map((item) => (
                <option key={item} value={item}>
                  {item}
                </option>
              ))}
            </NativeSelect>
          </label>

          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">source_authority</span>
            <NativeSelect
              onChange={(event) =>
                patchSearchParams({
                  sourceAuthority: event.currentTarget.value,
                  highlightedEvidenceId: "",
                })
              }
              value={sourceAuthority}
            >
              <option value="">全部</option>
              {sourceAuthorityOptions.map((item) => (
                <option key={item} value={item}>
                  {toAuthorityLabel(item)}
                </option>
              ))}
            </NativeSelect>
          </label>

          <div className="flex items-end">
            <Button
              onClick={() => setShowOnlyQualifiedEvidence((value) => !value)}
              size="sm"
              variant={showOnlyQualifiedEvidence ? "default" : "outline"}
            >
              {showOnlyQualifiedEvidence ? "仅看达标证据" : "显示全部证据"}
            </Button>
          </div>

          <div className="flex items-end">
            <Button onClick={clearFilters} size="sm" variant="outline">
              清空筛选
            </Button>
          </div>
        </CardContent>
      </Card>

      {hiddenFloorCount > 0 ? (
        <Card className="border-amber-500/30 bg-amber-500/5">
          <CardContent className="pt-4 text-xs text-amber-200">
            当前已默认隐藏 {hiddenFloorCount} 条兜底证据（未达标）。如需排查可切换“显示全部证据”。
          </CardContent>
        </Card>
      ) : null}

      {allEvidenceQuery.isLoading || filteredEvidenceQuery.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      ) : null}

      {allEvidenceQuery.isError || filteredEvidenceQuery.isError ? (
        <Card className="border-red-400/40">
          <CardContent className="pt-6 text-sm text-red-200">
            证据读取失败：
            {allEvidenceQuery.error?.message ?? filteredEvidenceQuery.error?.message ?? "unknown error"}
          </CardContent>
        </Card>
      ) : null}

      {highlightedEvidenceId && !hasHighlightedEvidence ? (
        <Card className="border-amber-400/40">
          <CardContent className="pt-6 text-sm text-amber-200">
            当前高亮 evidence ({highlightedEvidenceId}) 不在筛选结果中，请调整筛选条件。
          </CardContent>
        </Card>
      ) : null}

      {!filteredEvidenceQuery.isLoading &&
      !filteredEvidenceQuery.isError &&
      visibleEvidence.length === 0 ? (
        <Card>
          <CardContent className="pt-6 text-sm text-muted-foreground">
            当前筛选条件下没有 evidence。
          </CardContent>
        </Card>
      ) : null}

      <div className="space-y-4">
        {groupedEvidence.map(([groupKey, evidenceItems]) => (
          <Card key={groupKey}>
            <CardHeader className="pb-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <CardTitle className="text-base">{groupKey}</CardTitle>
                <Badge variant="secondary">{evidenceItems.length} 条证据</Badge>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              {evidenceItems.map((item) => {
                const sourceMeta = toSourceMeta(item.source_type);
                const SourceIcon = sourceMeta.icon;
                const isHighlighted = item.evidence_id === highlightedEvidenceId;
                const itemSourceAuthority = getSourceAuthority(item.metadata);
                const originalText = item.quote.trim().length > 0 ? item.quote : item.sanitized_text;
                const isEvidenceFloor = isEvidenceFloorRow(item);
                const floorReason = evidenceFloorReasonLabel(item.metadata);
                const summaryFragment = !isEvidenceFloor && isSummaryFragment(originalText);
                const translatedExcerpt =
                  typeof item.translated_excerpt === "string" && item.translated_excerpt.trim().length > 0
                    ? item.translated_excerpt
                    : null;
                return (
                  <article
                    className={cn(
                      "space-y-3 rounded-lg border border-border/90 bg-background/70 p-4",
                      isHighlighted && "border-primary bg-primary/10",
                    )}
                    key={item.evidence_id}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="inline-flex items-center gap-2 text-xs text-muted-foreground">
                        <SourceIcon className="h-3.5 w-3.5 text-primary" />
                        <span>{sourceMeta.label}</span>
                        <span>·</span>
                        <span>{formatDateTime(item.collected_at)}</span>
                      </div>
                      <span className="font-mono text-xs text-muted-foreground">{item.evidence_id}</span>
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      <Badge variant={itemSourceAuthority === "official" ? "success" : "secondary"}>
                        {toAuthorityLabel(itemSourceAuthority)}
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
                    {item.source_title ? <p className="text-sm font-medium text-foreground">{item.source_title}</p> : null}
                    <div className="space-y-2">
                      <div className="space-y-1">
                        <p className="text-xs text-muted-foreground">证据原文</p>
                        <p className="whitespace-pre-wrap text-sm leading-6 text-slate-200">{originalText}</p>
                      </div>
                      {translatedExcerpt ? (
                        <div className="space-y-1 rounded-md border border-primary/20 bg-primary/[0.06] p-2">
                          <p className="text-xs text-primary">报告语言摘要/译文</p>
                          <p className="whitespace-pre-wrap text-sm leading-6 text-slate-100">
                            {translatedExcerpt}
                          </p>
                        </div>
                      ) : null}
                    </div>
                    <div className="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
                      {isHighlighted ? <span className="rounded bg-primary/20 px-2 py-0.5 text-primary">报告高亮引用</span> : null}
                      {item.source_url ? (
                        <a
                          className="text-primary underline-offset-4 hover:underline"
                          href={item.source_url}
                          rel="noreferrer"
                          target="_blank"
                        >
                          打开原页面
                        </a>
                      ) : (
                        <span>无原始链接</span>
                      )}
                    </div>
                  </article>
                );
              })}
            </CardContent>
          </Card>
        ))}
      </div>
    </section>
  );
}
