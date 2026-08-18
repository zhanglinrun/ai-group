import { useEffect, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";

import { useRunReport } from "@/api/hooks";
import { EvidenceDrawer } from "@/components/EvidenceDrawer";
import { Logo } from "@/components/Logo";
import { ReportArticle } from "@/components/report/ReportArticle";
import { Skeleton } from "@/components/ui/skeleton";

export function SharedReportPage(): JSX.Element {
  const { runId } = useParams<{ runId: string }>();
  const [searchParams] = useSearchParams();
  const isPrintMode = searchParams.get("print") === "1";
  const [isEvidenceDrawerOpen, setIsEvidenceDrawerOpen] = useState(false);
  const [activeEvidenceIds, setActiveEvidenceIds] = useState<string[]>([]);
  const reportQuery = useRunReport(runId ?? "", { enabled: Boolean(runId) });

  function openEvidenceDrawer(evidenceIds: string[]): void {
    if (evidenceIds.length === 0 || !runId) {
      return;
    }
    setActiveEvidenceIds(evidenceIds);
    setIsEvidenceDrawerOpen(true);
  }

  useEffect(() => {
    if (!isPrintMode || reportQuery.isLoading || reportQuery.isError || reportQuery.data === undefined) {
      return;
    }
    window.setTimeout(() => window.print(), 150);
  }, [isPrintMode, reportQuery.data, reportQuery.isError, reportQuery.isLoading]);

  return (
    <section className={isPrintMode ? "space-y-4 py-6" : "space-y-6 py-8"}>
      <div className="no-print flex items-center justify-between">
        <Logo size="sm" />
        {isPrintMode ? null : (
          <Link to="/app" className="text-micro text-primary hover:underline">
            进入工作区
          </Link>
        )}
      </div>

      {reportQuery.isLoading && <Skeleton className="h-60 w-full" />}

      {reportQuery.isError && (
        <div className="rounded-lg border border-danger/30 bg-danger/5 p-4 text-caption text-danger">
          报告加载失败：{reportQuery.error.message}
        </div>
      )}

      {reportQuery.data && (
        <ReportArticle
          markdown={reportQuery.data.content_markdown}
          onEvidenceClick={openEvidenceDrawer}
        />
      )}

      <p className="text-center text-micro text-foreground-subtle">
        由熊博士 AI 深度调研生成 · 数据来源为公开信息
      </p>

      <EvidenceDrawer
        evidenceIds={activeEvidenceIds}
        onOpenChange={setIsEvidenceDrawerOpen}
        open={isEvidenceDrawerOpen}
        runId={runId ?? ""}
      />
    </section>
  );
}
