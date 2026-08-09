import {
  useMutation,
  type UseMutationResult,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from "@tanstack/react-query";

import { apiClient } from "@/api/client";
import { getRunFallbackPollMs, useRunEvents } from "@/api/sse";
import type {
  CompetitorDiffResponse,
  CompetitorSeedResponse,
  EvidenceListItemResponse,
  FollowUpAcceptedResponse,
  FollowUpRequest,
  IntakeCreateRequest,
  IntakeCreateResponse,
  IntakeSessionResponse,
  IntakeUserReply,
  PlanConfirmRequest,
  RunAcceptedResponse,
  RunComparisonsResponse,
  RunCreateRequest,
  RunCreateResponse,
  RunDetailResponse,
  RunKnowledgeResponse,
  RunListResponse,
  RunMetricsResponse,
  RunConclusionsResponse,
  RunResetRequest,
  RunReportResponse,
  RunTraceResponse,
  SkillCandidateListResponse,
  SkillCandidateReviewRequest,
  SkillCandidateReviewResponse,
  WatchlistCreateRequest,
  WatchlistDigestItemResponse,
  WatchlistItemResponse,
  WatchlistRefreshResponse,
  WatchlistUpdateRequest,
} from "@/api/types";

const RUNNING_POLL_INTERVAL_MS = getRunFallbackPollMs();

export interface RunsListQuery {
  status?: string;
  limit?: number;
  offset?: number;
}

export interface RunEvidenceQuery {
  competitor_id?: string;
  source_type?: string;
  evidence_ids?: string[];
}

export interface QueryBehaviorOptions {
  enabled?: boolean;
  refetchInterval?: number | false;
  /** Keep invalid/unauthorized detail queries from opening an SSE stream. */
  events?: boolean;
}

export interface SkillCandidatesQuery {
  status?: string;
  applies_to?: string;
  tag?: string;
  limit?: number;
  offset?: number;
}

export interface ReviewSkillCandidateMutationVariables {
  candidateId: string;
  reviewedBy: string;
}

async function fetchRunsList(query: RunsListQuery): Promise<RunListResponse> {
  const { data } = await apiClient.get<RunListResponse>("/api/runs", {
    params: {
      status: query.status,
      limit: query.limit ?? 20,
      offset: query.offset ?? 0,
    },
  });
  return data;
}

async function fetchRunDetail(runId: string): Promise<RunDetailResponse> {
  const { data } = await apiClient.get<RunDetailResponse>(`/api/runs/${runId}`);
  return data;
}

async function fetchRunTrace(runId: string): Promise<RunTraceResponse> {
  const { data } = await apiClient.get<RunTraceResponse>(`/api/runs/${runId}/trace`);
  // The Agent endpoint returns { run, steps, ... }, but older BFF responses
  // wrapped it in { data: ... } and could briefly omit `run` while a run was
  // being finalized. Normalize both shapes at the API boundary.
  const envelope = data as unknown as Record<string, unknown> | null;
  const payload =
    envelope !== null &&
    typeof envelope === "object" &&
    envelope.data !== null &&
    typeof envelope.data === "object"
      ? (envelope.data as Record<string, unknown>)
      : envelope ?? {};
  let run = payload.run as RunDetailResponse | undefined;
  if (run === undefined || typeof run !== "object" || typeof run.run_id !== "string") {
    // Keep a malformed trace response from taking down the complete report.
    // The detail endpoint is the authoritative fallback for the run header.
    try {
      const detailResponse = await apiClient.get<RunDetailResponse>(`/api/runs/${runId}`);
      run = detailResponse.data;
    } catch {
      run = {
        run_id: runId,
        user_query: "",
        title: null,
        domain_hint: null,
        reference_urls: [],
        status: "running",
        target_roles: [],
        competitors: [],
        started_at: new Date(0).toISOString(),
        finished_at: null,
        created_at: new Date(0).toISOString(),
      };
    }
  }
  // Older Agent/BFF responses may omit an empty trace collection while a run
  // is being finalized.  Keep the UI contract stable so detail, trace and
  // audit pages can render the report while those collections are empty.
  return {
    ...(payload as Partial<RunTraceResponse>),
    run,
    steps: Array.isArray(payload.steps) ? (payload.steps as RunTraceResponse["steps"]) : [],
    supervisor_decisions: Array.isArray(payload.supervisor_decisions)
      ? (payload.supervisor_decisions as RunTraceResponse["supervisor_decisions"])
      : [],
    llm_calls: Array.isArray(payload.llm_calls)
      ? (payload.llm_calls as RunTraceResponse["llm_calls"])
      : [],
    timeline: Array.isArray(payload.timeline)
      ? (payload.timeline as RunTraceResponse["timeline"])
      : [],
  };
}

async function fetchRunReport(runId: string): Promise<RunReportResponse> {
  const { data } = await apiClient.get<RunReportResponse>(`/api/runs/${runId}/report`);
  return data;
}

async function fetchRunMetrics(runId: string): Promise<RunMetricsResponse> {
  const { data } = await apiClient.get<RunMetricsResponse>(`/api/runs/${runId}/metrics`);
  return data;
}

async function fetchRunConclusions(runId: string): Promise<RunConclusionsResponse> {
  const { data } = await apiClient.get<RunConclusionsResponse>(`/api/runs/${runId}/conclusions`);
  return data;
}

async function fetchRunKnowledge(runId: string): Promise<RunKnowledgeResponse> {
  const { data } = await apiClient.get<RunKnowledgeResponse>(`/api/runs/${runId}/knowledge`);
  return data;
}

async function fetchRunComparisons(runId: string): Promise<RunComparisonsResponse> {
  const { data } = await apiClient.get<RunComparisonsResponse>(`/api/runs/${runId}/comparisons`);
  return data;
}

async function fetchWatchlist(): Promise<WatchlistItemResponse[]> {
  const { data } = await apiClient.get<WatchlistItemResponse[]>("/api/watchlist");
  return data;
}

async function fetchWatchlistDigest(): Promise<WatchlistDigestItemResponse[]> {
  const { data } = await apiClient.get<WatchlistDigestItemResponse[]>("/api/watchlist/digest");
  return data;
}

async function createWatchlistItem(payload: WatchlistCreateRequest): Promise<WatchlistItemResponse> {
  const { data } = await apiClient.post<WatchlistItemResponse>("/api/watchlist", payload);
  return data;
}

async function deleteWatchlistItem(watchId: string): Promise<WatchlistItemResponse> {
  const { data } = await apiClient.delete<WatchlistItemResponse>(`/api/watchlist/${watchId}`);
  return data;
}

async function patchWatchlistItem(
  watchId: string,
  payload: WatchlistUpdateRequest,
): Promise<WatchlistItemResponse> {
  const { data } = await apiClient.patch<WatchlistItemResponse>(`/api/watchlist/${watchId}`, payload);
  return data;
}

async function manualRefreshWatchlist(watchId: string): Promise<WatchlistRefreshResponse> {
  const { data } = await apiClient.post<WatchlistRefreshResponse>(`/api/watchlist/${watchId}/refresh`);
  return data;
}

async function fetchRunDiff(runId: string): Promise<CompetitorDiffResponse[]> {
  const { data } = await apiClient.get<CompetitorDiffResponse[]>(`/api/runs/${runId}/diff`);
  return data;
}

async function deleteRun(runId: string): Promise<{ run_id: string; deleted: boolean }> {
  const { data } = await apiClient.delete<{ run_id: string; deleted: boolean }>(`/api/runs/${runId}`);
  return data;
}

async function patchRun(
  runId: string,
  payload: {
    user_query?: string;
    title?: string;
    status?: "cancelled";
    cancel_reason?: string;
  },
): Promise<RunDetailResponse> {
  const { data } = await apiClient.patch<RunDetailResponse>(`/api/runs/${runId}`, payload);
  return data;
}

async function batchDeleteRuns(
  runIds: string[],
): Promise<{ deleted_count: number; not_found: string[] }> {
  const { data } = await apiClient.post<{ deleted_count: number; not_found: string[] }>(
    "/api/runs/batch-delete",
    { run_ids: runIds },
  );
  return data;
}

interface ClearRunsRequest {
  status?: "all" | "running" | "completed" | "degraded" | "failed" | "cancelled";
  keyword?: string;
  include_running?: boolean;
}

interface ClearRunsResponse {
  deleted_count: number;
  deleted_run_ids: string[];
  skipped_running_count: number;
  pruned_skill_candidate_refs: number;
}

async function clearRuns(payload: ClearRunsRequest): Promise<ClearRunsResponse> {
  const { data } = await apiClient.post<ClearRunsResponse>("/api/runs/clear", payload);
  return data;
}

async function fetchRunEvidence(
  runId: string,
  query: RunEvidenceQuery,
): Promise<EvidenceListItemResponse[]> {
  const { data } = await apiClient.get<unknown>(`/api/runs/${runId}/evidence`, {
    params: {
      competitor_id: query.competitor_id,
      source_type: query.source_type,
      // Keep the parameter name stable across Axios versions. The Agent
      // accepts a comma-separated list and older callers can omit it.
      evidence_id: query.evidence_ids?.join(","),
    },
  });
  return normalizeEvidenceRows(data);
}

/**
 * Agent normally returns an array, but older BFF deployments may wrap it in
 * `{ data: [...] }` or `{ items: [...] }`. Normalize at the API boundary so
 * every evidence consumer can safely treat the result as a list.
 */
function normalizeEvidenceRows(value: unknown): EvidenceListItemResponse[] {
  if (Array.isArray(value)) {
    return value.flatMap((item) => {
      if (!item || typeof item !== "object") {
        return [];
      }
      const record = item as Record<string, unknown>;
      const evidenceId =
        (typeof record.evidence_id === "string" && record.evidence_id) ||
        (typeof record.evidenceId === "string" && record.evidenceId) ||
        (typeof record.id === "string" && record.id);
      if (!evidenceId) {
        return [];
      }
      return [{ ...record, evidence_id: evidenceId } as EvidenceListItemResponse];
    });
  }
  if (!value || typeof value !== "object") {
    return [];
  }
  const record = value as Record<string, unknown>;
  for (const key of ["data", "items", "evidence", "results"]) {
    if (key in record) {
      return normalizeEvidenceRows(record[key]);
    }
  }
  return [];
}

async function fetchCompetitorSeeds(): Promise<CompetitorSeedResponse[]> {
  const { data } = await apiClient.get<CompetitorSeedResponse[]>("/api/demo-fixtures/competitors");
  return data;
}

async function createRun(payload: RunCreateRequest): Promise<RunCreateResponse> {
  const { data } = await apiClient.post<RunCreateResponse>("/api/runs", payload);
  return data;
}

interface CreateRunIntakeOptions {
  idempotencyKey?: string;
}

async function createRunIntake(
  payload: IntakeCreateRequest,
  options: CreateRunIntakeOptions = {},
): Promise<IntakeCreateResponse> {
  const headers =
    options.idempotencyKey !== undefined && options.idempotencyKey.length > 0
      ? { "Idempotency-Key": options.idempotencyKey }
      : undefined;
  const { data } = await apiClient.post<IntakeCreateResponse>("/api/runs/intake", payload, {
    headers,
  });
  if (!data || typeof data.run_id !== "string" || data.run_id.trim().length === 0) {
    throw new Error("Agent 服务未返回任务 ID，请检查 Agent 服务是否已启动并更新");
  }
  return data;
}

export async function fetchRunIntakeSession(
  runId: string,
): Promise<IntakeSessionResponse> {
  const { data } = await apiClient.get<IntakeSessionResponse>(
    `/api/runs/${runId}/intake-session`,
  );
  return data;
}

export interface ReplyRunIntakeVariables {
  runId: string;
  reply: IntakeUserReply;
}

async function replyRunIntake(
  runId: string,
  reply: IntakeUserReply,
): Promise<RunAcceptedResponse> {
  const { data } = await apiClient.post<RunAcceptedResponse>(
    `/api/runs/${runId}/intake/reply`,
    reply,
  );
  return data;
}

async function confirmRunPlan(
  runId: string,
  payload: PlanConfirmRequest,
): Promise<RunAcceptedResponse> {
  const { data } = await apiClient.post<RunAcceptedResponse>(
    `/api/runs/${runId}/plan/confirm`,
    payload,
  );
  return data;
}

async function submitRunFollowUp(
  runId: string,
  payload: FollowUpRequest,
): Promise<FollowUpAcceptedResponse> {
  const { data } = await apiClient.post<FollowUpAcceptedResponse>(
    `/api/runs/${runId}/follow-up`,
    payload,
  );
  return data;
}

async function resumeRun(runId: string): Promise<RunCreateResponse> {
  const { data } = await apiClient.post<RunCreateResponse>(`/api/runs/${runId}/resume`);
  return data;
}

async function resetRun(runId: string, payload: RunResetRequest): Promise<RunCreateResponse> {
  const { data } = await apiClient.post<RunCreateResponse>(`/api/runs/${runId}/reset`, payload);
  return data;
}

async function fetchSkillCandidates(
  query: SkillCandidatesQuery,
): Promise<SkillCandidateListResponse> {
  const { data } = await apiClient.get<SkillCandidateListResponse>("/api/skill-candidates", {
    params: {
      status: query.status,
      applies_to: query.applies_to,
      tag: query.tag,
      limit: query.limit ?? 20,
      offset: query.offset ?? 0,
    },
  });
  return data;
}

async function approveSkillCandidate(
  candidateId: string,
  payload: SkillCandidateReviewRequest,
): Promise<SkillCandidateReviewResponse> {
  const { data } = await apiClient.post<SkillCandidateReviewResponse>(
    `/api/skill-candidates/${candidateId}/approve`,
    payload,
  );
  return data;
}

async function rejectSkillCandidate(
  candidateId: string,
  payload: SkillCandidateReviewRequest,
): Promise<SkillCandidateReviewResponse> {
  const { data } = await apiClient.post<SkillCandidateReviewResponse>(
    `/api/skill-candidates/${candidateId}/reject`,
    payload,
  );
  return data;
}

export function useRunsList(
  query: RunsListQuery = {},
  options: QueryBehaviorOptions = {},
): UseQueryResult<RunListResponse, Error> {
  return useQuery({
    queryKey: ["runs", query.status ?? "", query.limit ?? 20, query.offset ?? 0],
    queryFn: () => fetchRunsList(query),
    enabled: options.enabled ?? true,
    refetchInterval: options.refetchInterval,
  });
}

export function useRunDetail(
  runId: string,
  options: QueryBehaviorOptions = {},
): UseQueryResult<RunDetailResponse, Error> {
  // Detail requests are also used to decide whether a run belongs to the
  // current user.  Do not open an SSE stream until that check succeeds;
  // otherwise a direct link to another user's run creates a repeating 404
  // stream and noisy gateway errors before the page can render its error card.
  useRunEvents(options.events === false ? "" : runId);
  return useQuery({
    queryKey: ["run-detail", runId],
    queryFn: () => fetchRunDetail(runId),
    enabled: Boolean(runId) && (options.enabled ?? true),
    refetchInterval: (query) =>
      query.state.data?.status === "running" ? RUNNING_POLL_INTERVAL_MS : false,
  });
}

export function useRunTrace(
  runId: string,
  options: QueryBehaviorOptions = {},
): UseQueryResult<RunTraceResponse, Error> {
  useRunEvents(options.events === false ? "" : runId);
  return useQuery({
    queryKey: ["run-trace", runId],
    queryFn: () => fetchRunTrace(runId),
    enabled: Boolean(runId) && (options.enabled ?? true),
    refetchInterval: (query) =>
      query.state.data?.run?.status === "running" ? RUNNING_POLL_INTERVAL_MS : false,
  });
}

export function useRunReport(
  runId: string,
  options: QueryBehaviorOptions = {},
): UseQueryResult<RunReportResponse, Error> {
  return useQuery({
    queryKey: ["run-report", runId],
    queryFn: () => fetchRunReport(runId),
    enabled: Boolean(runId) && (options.enabled ?? true),
    refetchInterval: options.refetchInterval,
  });
}

export function useRunMetrics(
  runId: string,
  options: QueryBehaviorOptions = {},
): UseQueryResult<RunMetricsResponse, Error> {
  return useQuery({
    queryKey: ["run-metrics", runId],
    queryFn: () => fetchRunMetrics(runId),
    enabled: Boolean(runId) && (options.enabled ?? true),
    refetchInterval: options.refetchInterval,
  });
}

export function useRunConclusions(
  runId: string,
  options: QueryBehaviorOptions = {},
): UseQueryResult<RunConclusionsResponse, Error> {
  return useQuery({
    queryKey: ["run-conclusions", runId],
    queryFn: () => fetchRunConclusions(runId),
    enabled: Boolean(runId) && (options.enabled ?? true),
    refetchInterval: options.refetchInterval,
  });
}

export function useRunKnowledge(
  runId: string,
  options: QueryBehaviorOptions = {},
): UseQueryResult<RunKnowledgeResponse, Error> {
  return useQuery({
    queryKey: ["run-knowledge", runId],
    queryFn: () => fetchRunKnowledge(runId),
    enabled: Boolean(runId) && (options.enabled ?? true),
    refetchInterval: options.refetchInterval,
  });
}

export function useRunComparisons(
  runId: string,
  options: QueryBehaviorOptions = {},
): UseQueryResult<RunComparisonsResponse, Error> {
  return useQuery({
    queryKey: ["run-comparisons", runId],
    queryFn: () => fetchRunComparisons(runId),
    enabled: Boolean(runId) && (options.enabled ?? true),
    refetchInterval: options.refetchInterval,
  });
}

export function useWatchlist(): UseQueryResult<WatchlistItemResponse[], Error> {
  return useQuery({
    queryKey: ["watchlist"],
    queryFn: fetchWatchlist,
  });
}

export function useWatchlistDigest(): UseQueryResult<WatchlistDigestItemResponse[], Error> {
  return useQuery({
    queryKey: ["watchlist-digest"],
    queryFn: fetchWatchlistDigest,
  });
}

export function useRunEvidence(
  runId: string,
  query: RunEvidenceQuery = {},
  options: QueryBehaviorOptions = {},
): UseQueryResult<EvidenceListItemResponse[], Error> {
  return useQuery({
    queryKey: [
      "run-evidence",
      runId,
      query.competitor_id ?? "",
      query.source_type ?? "",
      query.evidence_ids?.join(",") ?? "",
    ],
    queryFn: () => fetchRunEvidence(runId, query),
    enabled: Boolean(runId) && (options.enabled ?? true),
    refetchInterval: options.refetchInterval,
  });
}

export function useCompetitorSeeds(): UseQueryResult<CompetitorSeedResponse[], Error> {
  return useQuery({
    queryKey: ["competitor-seeds"],
    queryFn: fetchCompetitorSeeds,
  });
}

export function useCreateRun(): UseMutationResult<RunCreateResponse, Error, RunCreateRequest> {
  return useMutation({
    mutationFn: createRun,
  });
}

export interface CreateRunIntakeVariables {
  payload: IntakeCreateRequest;
  idempotencyKey?: string;
}

export function useCreateRunIntake(): UseMutationResult<IntakeCreateResponse, Error, CreateRunIntakeVariables> {
  return useMutation({
    mutationFn: ({ payload, idempotencyKey }) => createRunIntake(payload, { idempotencyKey }),
    meta: { errorToast: false },
  });
}

export function useReplyRunIntake(): UseMutationResult<
  RunAcceptedResponse,
  Error,
  ReplyRunIntakeVariables
> {
  return useMutation({
    mutationFn: ({ runId, reply }) => replyRunIntake(runId, reply),
  });
}

export interface ConfirmRunPlanVariables {
  runId: string;
  payload: PlanConfirmRequest;
}

export function useConfirmRunPlan(): UseMutationResult<
  RunAcceptedResponse,
  Error,
  ConfirmRunPlanVariables
> {
  return useMutation({
    mutationFn: ({ runId, payload }) => confirmRunPlan(runId, payload),
  });
}

export interface SubmitRunFollowUpVariables {
  runId: string;
  payload: FollowUpRequest;
}

export function useSubmitRunFollowUp(): UseMutationResult<
  FollowUpAcceptedResponse,
  Error,
  SubmitRunFollowUpVariables
> {
  return useMutation({
    mutationFn: ({ runId, payload }) => submitRunFollowUp(runId, payload),
  });
}

export function useResumeRun(): UseMutationResult<RunCreateResponse, Error, string> {
  return useMutation({
    mutationFn: resumeRun,
  });
}

export interface ResetRunMutationVariables {
  runId: string;
  resetTo: RunResetRequest["reset_to"];
}

export function useResetRun(): UseMutationResult<RunCreateResponse, Error, ResetRunMutationVariables> {
  return useMutation({
    mutationFn: ({ runId, resetTo }) => resetRun(runId, { reset_to: resetTo }),
  });
}

export function useCreateWatchlistItem(): UseMutationResult<
  WatchlistItemResponse,
  Error,
  WatchlistCreateRequest
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createWatchlistItem,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["watchlist"] }),
        queryClient.invalidateQueries({ queryKey: ["watchlist-digest"] }),
      ]);
    },
  });
}

export function useDeleteWatchlistItem(): UseMutationResult<WatchlistItemResponse, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: deleteWatchlistItem,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["watchlist"] }),
        queryClient.invalidateQueries({ queryKey: ["watchlist-digest"] }),
      ]);
    },
  });
}

export function usePatchWatchlistItem(): UseMutationResult<
  WatchlistItemResponse,
  Error,
  { watchId: string; payload: WatchlistUpdateRequest }
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ watchId, payload }) => patchWatchlistItem(watchId, payload),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["watchlist"] }),
        queryClient.invalidateQueries({ queryKey: ["watchlist-digest"] }),
      ]);
    },
  });
}

export function useManualRefreshWatchlist(): UseMutationResult<
  WatchlistRefreshResponse,
  Error,
  string
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: manualRefreshWatchlist,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["watchlist-digest"] });
    },
  });
}

export function useRunDiff(
  runId: string | null | undefined,
): UseQueryResult<CompetitorDiffResponse[], Error> {
  return useQuery({
    queryKey: ["run-diff", runId],
    queryFn: () => fetchRunDiff(runId!),
    enabled: Boolean(runId),
  });
}

export function useSkillCandidates(
  query: SkillCandidatesQuery = {},
  options?: { errorToast?: boolean },
): UseQueryResult<SkillCandidateListResponse, Error> {
  return useQuery({
    queryKey: [
      "skill-candidates",
      query.status ?? "",
      query.applies_to ?? "",
      query.tag ?? "",
      query.limit ?? 20,
      query.offset ?? 0,
    ],
    queryFn: () => fetchSkillCandidates(query),
    meta: { errorToast: options?.errorToast ?? true },
  });
}

export function useApproveCandidate(): UseMutationResult<
  SkillCandidateReviewResponse,
  Error,
  ReviewSkillCandidateMutationVariables
> {
  return useMutation({
    mutationFn: ({ candidateId, reviewedBy }) =>
      approveSkillCandidate(candidateId, { reviewed_by: reviewedBy }),
  });
}

export function useRejectCandidate(): UseMutationResult<
  SkillCandidateReviewResponse,
  Error,
  ReviewSkillCandidateMutationVariables
> {
  return useMutation({
    mutationFn: ({ candidateId, reviewedBy }) =>
      rejectSkillCandidate(candidateId, { reviewed_by: reviewedBy }),
  });
}

export interface PatchRunVariables {
  runId: string;
  payload: {
    user_query?: string;
    title?: string;
    status?: "cancelled";
    cancel_reason?: string;
  };
}

export function useDeleteRun(): UseMutationResult<
  { run_id: string; deleted: boolean },
  Error,
  string
> {
  return useMutation({
    mutationFn: deleteRun,
  });
}

export function usePatchRun(): UseMutationResult<RunDetailResponse, Error, PatchRunVariables> {
  return useMutation({
    mutationFn: ({ runId, payload }) => patchRun(runId, payload),
  });
}

export function useBatchDeleteRuns(): UseMutationResult<
  { deleted_count: number; not_found: string[] },
  Error,
  string[]
> {
  return useMutation({
    mutationFn: batchDeleteRuns,
  });
}

export function useClearRuns(): UseMutationResult<
  ClearRunsResponse,
  Error,
  ClearRunsRequest
> {
  return useMutation({
    mutationFn: clearRuns,
  });
}
