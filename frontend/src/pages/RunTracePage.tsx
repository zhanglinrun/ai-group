import { Link, useParams } from "react-router-dom";

import { useRunDetail, useRunTrace } from "@/api/hooks";
import { useRunEvents } from "@/api/sse";
import { RunTraceDag } from "@/components/dag/RunTraceDag";
import { RunBreadcrumb } from "@/components/RunBreadcrumb";
import { LlmCallsTable } from "@/components/trace/LlmCallsTable";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { buildEvidenceLinkFromToolArgs } from "@/lib/evidenceLinks";
import { formatDateTime } from "@/lib/format";

export function RunTracePage(): JSX.Element {
  const { runId: runIdFromParams } = useParams<{ runId: string }>();
  const runId = runIdFromParams ?? "";
  const detailQuery = useRunDetail(runId, { events: false });
  const traceQuery = useRunTrace(runId, { events: false });
  useRunEvents(detailQuery.data && !detailQuery.isError ? runId : "");

  return (
    <section className="space-y-4 rounded-lg border border-border bg-black/70 p-4 font-mono text-sm text-gray-100">
      <header className="space-y-2">
        <RunBreadcrumb
          run={detailQuery.data}
          current="执行过程"
          className="text-gray-400 [&_a:hover]:text-gray-100 [&_span]:text-gray-100 [&_svg]:text-gray-500"
        />
        <h1 className="text-xl font-semibold">开发者视图 / 执行追踪</h1>
        <div className="text-xs text-gray-400">run_id: {runId}</div>
      </header>

      {traceQuery.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-24 w-full bg-gray-700" />
          <Skeleton className="h-24 w-full bg-gray-700" />
        </div>
      ) : null}

      {traceQuery.isError ? (
        <Card className="border-red-400/40 bg-red-900/20">
          <CardContent className="pt-6 text-red-200">{traceQuery.error.message}</CardContent>
        </Card>
      ) : null}

      {traceQuery.data ? (
        <Tabs defaultValue="dag">
          <TabsList className="bg-gray-900">
            <TabsTrigger value="dag">DAG</TabsTrigger>
            <TabsTrigger value="steps">Steps</TabsTrigger>
            <TabsTrigger value="decisions">Supervisor decisions</TabsTrigger>
            <TabsTrigger value="llm">LLM calls</TabsTrigger>
          </TabsList>

          <TabsContent value="dag">
            <Card className="bg-black/40">
              <CardHeader>
                <CardTitle className="text-base text-gray-100">DAG 决策回放</CardTitle>
              </CardHeader>
              <CardContent>
                <RunTraceDag trace={traceQuery.data} />
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="steps">
            <Card className="bg-black/40">
              <CardHeader>
                <CardTitle className="text-base text-gray-100">Steps</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                {traceQuery.data.steps.map((step) => (
                  <div className="rounded border border-gray-700 p-3" key={step.step_id}>
                    <p>
                      <span className="text-gray-400">[{formatDateTime(step.created_at)}]</span> {step.agent_name} ·{" "}
                      {step.status}
                    </p>
                    <p className="text-xs text-gray-500">{step.step_id}</p>
                  </div>
                ))}
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="decisions">
            <Card className="bg-black/40">
              <CardHeader>
                <CardTitle className="text-base text-gray-100">Supervisor decisions</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                {traceQuery.data.supervisor_decisions.map((decision) => {
                  const evidenceLink = buildEvidenceLinkFromToolArgs(runId, decision.tool_args);
                  return (
                    <div className="rounded border border-gray-700 p-3" key={decision.id}>
                      <p>
                        <span className="text-gray-400">[{formatDateTime(decision.created_at)}]</span> iter=
                        {decision.iteration} · {decision.chosen_tool}
                      </p>
                      <p className="mt-1 text-xs text-gray-500">{decision.reasoning_summary}</p>
                      {evidenceLink !== null ? (
                        <Link className="mt-2 inline-flex text-xs text-primary hover:underline" to={evidenceLink}>
                          查看相关证据
                        </Link>
                      ) : null}
                    </div>
                  );
                })}
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="llm">
            <Card className="bg-black/40">
              <CardHeader>
                <CardTitle className="text-base text-gray-100">LLM calls</CardTitle>
              </CardHeader>
              <CardContent>
                <LlmCallsTable calls={traceQuery.data.llm_calls} steps={traceQuery.data.steps} />
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      ) : null}
    </section>
  );
}
