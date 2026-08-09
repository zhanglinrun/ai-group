import { useMemo } from "react";

import type { LLMCallTraceResponse, StepTraceResponse } from "@/api/types";
import { formatDateTime } from "@/lib/format";

export interface LlmCallsTableProps {
  calls: LLMCallTraceResponse[];
  steps: StepTraceResponse[];
}

function formatNullableNumber(value: number | null, suffix = ""): string {
  return value === null ? "-" : `${value.toLocaleString()}${suffix}`;
}

function formatTokenPair(promptTokens: number | null, completionTokens: number | null): string {
  return `${formatNullableNumber(promptTokens)} / ${formatNullableNumber(completionTokens)}`;
}

export function LlmCallsTable({ calls, steps }: LlmCallsTableProps): JSX.Element {
  const stepById = useMemo(() => new Map(steps.map((step) => [step.step_id, step])), [steps]);

  if (calls.length === 0) {
    return <p className="text-xs text-gray-400">暂无 LLM 调用记录。</p>;
  }

  return (
    <div className="overflow-x-auto">
      <table className="min-w-[1080px] text-left text-xs">
        <thead className="border-b border-gray-700 text-gray-400">
          <tr>
            <th className="px-2 py-2 font-medium">time</th>
            <th className="px-2 py-2 font-medium">agent</th>
            <th className="px-2 py-2 font-medium">slot</th>
            <th className="px-2 py-2 font-medium">provider/model</th>
            <th className="px-2 py-2 font-medium">tokens p/c</th>
            <th className="px-2 py-2 font-medium">latency</th>
            <th className="px-2 py-2 font-medium">retry</th>
            <th className="px-2 py-2 font-medium">fallback</th>
            <th className="px-2 py-2 font-medium">prompt preview</th>
          </tr>
        </thead>
        <tbody>
          {calls.map((call) => {
            const step = stepById.get(call.step_id);
            const fallbackLabel = call.fallback_used ? call.fallback_reason ?? "used" : "-";
            return (
              <tr className="border-b border-gray-800 align-top" key={call.id}>
                <td className="px-2 py-2 text-gray-400">{formatDateTime(call.created_at)}</td>
                <td className="px-2 py-2">
                  <div>{step?.agent_name ?? "-"}</div>
                  <div className="mt-1 max-w-36 truncate text-[10px] text-gray-500" title={call.step_id}>
                    {call.step_id}
                  </div>
                </td>
                <td className="px-2 py-2">{call.model_slot}</td>
                <td className="px-2 py-2">
                  <div>{call.provider ?? "-"}</div>
                  <div className="mt-1 max-w-40 truncate text-gray-500" title={call.model_name ?? undefined}>
                    {call.model_name ?? "-"}
                  </div>
                </td>
                <td className="px-2 py-2 tabular-nums">
                  {formatTokenPair(call.prompt_tokens, call.completion_tokens)}
                </td>
                <td className="px-2 py-2 tabular-nums">
                  {formatNullableNumber(call.latency_ms, "ms")}
                </td>
                <td className="px-2 py-2 tabular-nums">{call.retry_count}</td>
                <td className="px-2 py-2">
                  <span className={call.fallback_used ? "text-amber-300" : "text-gray-500"}>
                    {fallbackLabel}
                  </span>
                </td>
                <td className="px-2 py-2">
                  {call.error ? (
                    <p className="max-w-72 text-red-300" title={call.error}>
                      {call.error}
                    </p>
                  ) : (
                    <p className="max-w-72 text-gray-400" title={call.prompt_preview ?? undefined}>
                      {call.prompt_preview ?? "-"}
                    </p>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
