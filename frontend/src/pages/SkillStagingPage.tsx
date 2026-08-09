import { useState } from "react";
import { Link } from "react-router-dom";

import { useApproveCandidate, useRejectCandidate, useSkillCandidates } from "@/api/hooks";
import type { PromotedArtifactResponse } from "@/api/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { formatDateTime } from "@/lib/format";

export function SkillStagingPage(): JSX.Element {
  const [statusFilter, setStatusFilter] = useState("staging");
  const [appliesToFilter, setAppliesToFilter] = useState("");
  const [tagFilter, setTagFilter] = useState("");
  const [reviewedBy, setReviewedBy] = useState("owner_wh");
  const [pendingCandidateId, setPendingCandidateId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [promotionHints, setPromotionHints] = useState<PromotedArtifactResponse[]>([]);

  const candidatesQuery = useSkillCandidates({
    status: statusFilter === "all" ? undefined : statusFilter,
    applies_to: appliesToFilter.trim() || undefined,
    tag: tagFilter.trim() || undefined,
    limit: 50,
    offset: 0,
  });
  const approveMutation = useApproveCandidate();
  const rejectMutation = useRejectCandidate();

  async function reviewCandidate(
    candidateId: string,
    action: "approve" | "reject",
  ): Promise<void> {
    const reviewer = reviewedBy.trim();
    if (!reviewer) {
      setActionError("reviewed_by 不能为空。");
      return;
    }

    setPendingCandidateId(candidateId);
    try {
      if (action === "approve") {
        const result = await approveMutation.mutateAsync({ candidateId, reviewedBy: reviewer });
        setPromotionHints(result.promoted_artifacts);
      } else {
        await rejectMutation.mutateAsync({ candidateId, reviewedBy: reviewer });
        setPromotionHints([]);
      }
      setActionError(null);
      await candidatesQuery.refetch();
    } catch (error) {
      if (error instanceof Error) {
        setActionError(error.message);
      } else {
        setActionError("审核操作失败，请稍后重试。");
      }
    } finally {
      setPendingCandidateId(null);
    }
  }

  return (
    <section className="space-y-4">
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold">Skill 审核台</h1>
        <p className="text-sm text-muted-foreground">
          查看 Curator 生成的候选项，进行通过/拒绝审核。
        </p>
      </header>

      <Card>
        <CardContent className="grid gap-3 pt-6 md:grid-cols-4">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">状态筛选</span>
            <NativeSelect
              onChange={(event) => setStatusFilter(event.target.value)}
              value={statusFilter}
            >
              <option value="all">全部</option>
              <option value="staging">待审核 (staging)</option>
              <option value="approved">已通过</option>
              <option value="rejected">已拒绝</option>
            </NativeSelect>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">applies_to</span>
            <Input
              onChange={(event) => setAppliesToFilter(event.target.value)}
              placeholder="qa_rule / prompt_template / source_routing"
              value={appliesToFilter}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">tag</span>
            <Input
              onChange={(event) => setTagFilter(event.target.value)}
              placeholder="generic"
              value={tagFilter}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">reviewed_by</span>
            <Input
              onChange={(event) => setReviewedBy(event.target.value)}
              placeholder="owner_wh"
              value={reviewedBy}
            />
          </label>
        </CardContent>
      </Card>

      {actionError ? (
        <Card className="border-red-400/40">
          <CardContent className="pt-6 text-sm text-red-200">{actionError}</CardContent>
        </Card>
      ) : null}

      {promotionHints.length > 0 ? (
        <Card className="border-primary/40">
          <CardHeader className="pb-2">
            <CardTitle className="text-base">已写回 backend/skills</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-xs">
            <p className="text-muted-foreground">
              以下文件已更新，请手动执行 git commit 以完成版本入库：
            </p>
            {promotionHints.map((item) => (
              <div
                className="rounded-md border border-border bg-muted/30 px-3 py-2 font-mono"
                key={`${item.path}-${item.entry_id}`}
              >
                <p>{item.path}</p>
                <p className="text-muted-foreground">
                  action={item.action} · entry_id={item.entry_id}
                </p>
              </div>
            ))}
          </CardContent>
        </Card>
      ) : null}

      {candidatesQuery.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : null}

      {candidatesQuery.isError ? (
        <Card className="border-red-400/40">
          <CardContent className="pt-6 text-sm text-red-200">
            {candidatesQuery.error.message}
          </CardContent>
        </Card>
      ) : null}

      {!candidatesQuery.isLoading && !candidatesQuery.isError && candidatesQuery.data?.items.length === 0 ? (
        <Card>
          <CardContent className="pt-6 text-sm text-muted-foreground">
            当前筛选条件下没有候选项。
          </CardContent>
        </Card>
      ) : null}

      {!candidatesQuery.isLoading && !candidatesQuery.isError ? (
        <div className="space-y-3">
          {candidatesQuery.data?.items.map((candidate) => {
            const isPending = pendingCandidateId === candidate.id;
            return (
              <Card key={candidate.id}>
                <CardHeader className="pb-3">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <CardTitle className="font-mono text-sm">{candidate.id}</CardTitle>
                    <div className="flex items-center gap-2">
                      <Badge variant="outline">{candidate.candidate_type}</Badge>
                      <Badge variant="secondary">{candidate.confidence}</Badge>
                      <Badge>{candidate.status}</Badge>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="space-y-3 text-sm">
                  <p className="text-muted-foreground">{candidate.rationale}</p>
                  <p className="text-xs text-muted-foreground">
                    applies_to: {candidate.applies_to} · created: {formatDateTime(candidate.created_at)}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    tags: {candidate.tags.join(", ") || "none"}
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {candidate.supporting_run_ids.map((runId) => (
                      <Link
                        className="rounded-md border border-border px-2 py-1 text-xs text-muted-foreground hover:border-primary hover:text-foreground"
                        key={runId}
                        to={`/app/runs/${runId}`}
                      >
                        {runId}
                      </Link>
                    ))}
                  </div>
                  <pre className="overflow-x-auto rounded-md border border-border bg-muted/30 p-3 text-xs leading-5 text-muted-foreground">
                    {JSON.stringify(candidate.payload, null, 2)}
                  </pre>
                  {candidate.error ? (
                    <p className="text-xs text-red-200">error: {candidate.error}</p>
                  ) : null}
                  {candidate.status === "staging" ? (
                    <div className="flex items-center gap-2">
                      <Button
                        disabled={isPending}
                        onClick={() => reviewCandidate(candidate.id, "approve")}
                        size="sm"
                      >
                        通过并生效
                      </Button>
                      <Button
                        disabled={isPending}
                        onClick={() => reviewCandidate(candidate.id, "reject")}
                        size="sm"
                        variant="outline"
                      >
                        拒绝
                      </Button>
                    </div>
                  ) : null}
                </CardContent>
              </Card>
            );
          })}
        </div>
      ) : null}
    </section>
  );
}
