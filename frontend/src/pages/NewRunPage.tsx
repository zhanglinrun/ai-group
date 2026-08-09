import type { FormEvent } from "react";
import type { LucideIcon } from "lucide-react";
import { ChevronDown, Compass, CopyPlus, Link2, Rocket, Target, UserRoundSearch, Users } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";

import { useCompetitorSeeds, useCreateRun, useCreateWatchlistItem } from "@/api/hooks";
import type { ReportDepth } from "@/api/types";
import { IntakeModeSwitcher } from "@/components/intake/IntakeModeSwitcher";
import { ReportDepthSelector } from "@/components/intake/ReportDepthSelector";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { pushToast } from "@/components/ui/toaster";

interface RoleOption {
  id: string;
  label: string;
  description: string;
  icon: LucideIcon;
}

interface RunTemplate {
  id: string;
  label: string;
  userQuery: string;
  competitors: string[];
  targetRoles: string[];
  domainHint: string;
  referenceUrls: string[];
}

const LAST_RUN_DRAFT_STORAGE_KEY = "xiongdoctor.lastRunDraft";

const ROLE_OPTIONS: RoleOption[] = [
  { id: "pm", label: "产品经理", description: "关注功能差异、定价与用户反馈", icon: Target },
  { id: "founder", label: "创业者", description: "关注市场机会、定位与风险", icon: Rocket },
  { id: "sales", label: "销售", description: "关注竞品卖点、报价与异议回答", icon: Users },
  { id: "investor", label: "投资人", description: "关注竞争格局与增长潜力", icon: UserRoundSearch },
];

const RUN_TEMPLATES: RunTemplate[] = [
  {
    id: "ai-coding",
    label: "AI Coding 工具",
    userQuery: "对比 Cursor、Windsurf、TRAE 与 Claude Code 的功能差异、定价策略和用户反馈。",
    competitors: ["Cursor", "Windsurf", "TRAE", "Claude Code"],
    targetRoles: ["pm", "founder"],
    domainHint: "AI coding assistant, developer productivity",
    referenceUrls: [
      "https://cursor.com/pricing",
      "https://windsurf.com/pricing",
    ],
  },
  {
    id: "knowledge-saas",
    label: "知识协作 SaaS",
    userQuery: "分析 Notion 与 Obsidian 在协作能力、知识组织和付费转化上的差异。",
    competitors: ["Notion", "Obsidian"],
    targetRoles: ["pm", "founder"],
    domainHint: "knowledge management SaaS",
    referenceUrls: [],
  },
  {
    id: "pm-tools",
    label: "项目协作工具",
    userQuery: "比较 Linear、Jira 与 ClickUp 在团队协作效率和上手成本上的优劣。",
    competitors: ["Linear", "Jira", "ClickUp"],
    targetRoles: ["pm", "founder", "sales"],
    domainHint: "project management tools",
    referenceUrls: [],
  },
];

function normalizeReportDepth(raw: unknown): ReportDepth {
  if (raw === "deep" || raw === "quick") {
    return raw;
  }
  if (raw === "debug" && import.meta.env.DEV) {
    return "debug";
  }
  return "quick";
}

export function NewRunPage(): JSX.Element {
  const navigate = useNavigate();
  const competitorSeedsQuery = useCompetitorSeeds();
  const createRunMutation = useCreateRun();
  const createWatchlistItemMutation = useCreateWatchlistItem();

  const [userQuery, setUserQuery] = useState("AI Coding 工具竞争格局分析");
  const [domainHint, setDomainHint] = useState("");
  const [selfProduct, setSelfProduct] = useState("");
  const [marketScope, setMarketScope] = useState("");
  const [timeContext, setTimeContext] = useState("");
  const [competitorInput, setCompetitorInput] = useState("");
  const [selectedCompetitors, setSelectedCompetitors] = useState<string[]>([]);
  const [bulkCompetitorInput, setBulkCompetitorInput] = useState("");
  const [referenceUrlInput, setReferenceUrlInput] = useState("");
  const [referenceUrls, setReferenceUrls] = useState<string[]>([]);
  const [targetRoles, setTargetRoles] = useState<string[]>(["pm", "founder"]);
  const [reportDepth, setReportDepth] = useState<ReportDepth>("quick");
  const [addToWatchlist, setAddToWatchlist] = useState(false);

  const competitorSeeds = competitorSeedsQuery.data ?? [];
  const competitorSuggestions = useMemo(() => {
    const keyword = competitorInput.trim().toLowerCase();
    const matched = competitorSeeds.filter((item) => {
      if (!keyword) {
        return true;
      }
      if (item.display_name.toLowerCase().includes(keyword)) {
        return true;
      }
      if (item.id.toLowerCase().includes(keyword)) {
        return true;
      }
      return item.aliases.some((alias) => alias.toLowerCase().includes(keyword));
    });
    return matched.slice(0, 8);
  }, [competitorInput, competitorSeeds]);

  useEffect(() => {
    if (competitorSeeds.length === 0 || selectedCompetitors.length > 0) {
      return;
    }
    setSelectedCompetitors(competitorSeeds.slice(0, 2).map((item) => item.display_name));
  }, [competitorSeeds, selectedCompetitors.length]);

  useEffect(() => {
    const serializedDraft = localStorage.getItem(LAST_RUN_DRAFT_STORAGE_KEY);
    if (!serializedDraft) {
      return;
    }
    try {
      const parsed = JSON.parse(serializedDraft) as {
        userQuery?: string;
        competitors?: string[];
        domainHint?: string;
        referenceUrls?: string[];
        targetRoles?: string[];
        reportDepth?: ReportDepth;
      };
      if (typeof parsed.userQuery === "string" && parsed.userQuery.trim()) {
        setUserQuery(parsed.userQuery.trim());
      }
      if (Array.isArray(parsed.competitors) && parsed.competitors.length > 0) {
        setSelectedCompetitors(parsed.competitors.filter((item): item is string => typeof item === "string"));
      }
      if (typeof parsed.domainHint === "string") {
        setDomainHint(parsed.domainHint);
      }
      if (Array.isArray(parsed.referenceUrls)) {
        setReferenceUrls(parsed.referenceUrls.filter((item): item is string => typeof item === "string"));
      }
      if (Array.isArray(parsed.targetRoles) && parsed.targetRoles.length > 0) {
        setTargetRoles(parsed.targetRoles.filter((item): item is string => typeof item === "string"));
      }
      setReportDepth(normalizeReportDepth(parsed.reportDepth));
    } catch {
      // Ignore malformed persisted drafts and continue with defaults.
    }
  }, []);

  const canSubmit =
    userQuery.trim().length > 0 &&
    targetRoles.length > 0 &&
    !createRunMutation.isPending;

  function addCompetitor(rawValue: string): void {
    const value = rawValue.trim();
    if (!value) {
      return;
    }
    setSelectedCompetitors((prev) => {
      if (prev.includes(value)) {
        return prev;
      }
      return [...prev, value];
    });
    setCompetitorInput("");
  }

  function applyTemplate(template: RunTemplate): void {
    setUserQuery(template.userQuery);
    setSelectedCompetitors(template.competitors);
    setTargetRoles(template.targetRoles);
    setDomainHint(template.domainHint);
    setReferenceUrls(template.referenceUrls);
    setCompetitorInput("");
    setBulkCompetitorInput("");
    pushToast({
      title: "模板已填充",
      description: `已应用「${template.label}」模板，可按需再编辑。`,
      variant: "success",
    });
  }

  function importCompetitorsFromBulkInput(): void {
    const items = bulkCompetitorInput
      .split(/\r?\n/)
      .map((item) => item.trim())
      .filter((item) => item.length > 0);
    if (items.length === 0) {
      return;
    }
    setSelectedCompetitors((prev) => {
      const merged = [...prev];
      for (const item of items) {
        if (!merged.includes(item)) {
          merged.push(item);
        }
      }
      return merged;
    });
    setBulkCompetitorInput("");
    pushToast({
      title: "竞品已批量导入",
      description: `新增 ${items.length} 个候选竞品。`,
      variant: "success",
    });
  }

  function removeCompetitor(value: string): void {
    setSelectedCompetitors((prev) => prev.filter((item) => item !== value));
  }

  function addReferenceUrl(rawValue: string): void {
    const value = rawValue.trim();
    if (!value) {
      return;
    }
    setReferenceUrls((prev) => {
      if (prev.includes(value)) {
        return prev;
      }
      return [...prev, value];
    });
    setReferenceUrlInput("");
  }

  function removeReferenceUrl(value: string): void {
    setReferenceUrls((prev) => prev.filter((item) => item !== value));
  }

  function toggleRole(roleId: string): void {
    setTargetRoles((prev) => {
      if (prev.includes(roleId)) {
        return prev.filter((item) => item !== roleId);
      }
      return [...prev, roleId];
    });
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (!canSubmit) {
      return;
    }
    try {
      const payload = {
        user_query: userQuery.trim(),
        competitors: selectedCompetitors,
        domain_hint: domainHint.trim() ? domainHint.trim() : null,
        reference_urls: referenceUrls.length > 0 ? referenceUrls : null,
        target_roles: targetRoles,
        report_depth: reportDepth,
        self_product: selfProduct.trim() ? selfProduct.trim() : null,
        market_scope: marketScope.trim() ? marketScope.trim() : null,
        time_context: timeContext.trim() ? timeContext.trim() : null,
      };
      const created = await createRunMutation.mutateAsync(payload);
      if (addToWatchlist) {
        const failedCompetitors: string[] = [];
        await Promise.all(
          payload.competitors.map(async (competitorId) => {
            try {
              await createWatchlistItemMutation.mutateAsync({ competitor_id: competitorId });
            } catch (error) {
              if (error instanceof Error && error.message.includes("WATCHLIST_ALREADY_EXISTS")) {
                return;
              }
              failedCompetitors.push(competitorId);
            }
          }),
        );
        if (failedCompetitors.length > 0) {
          pushToast({
            title: "部分竞品未加入追踪列表",
            description: failedCompetitors.join("、"),
            variant: "warning",
          });
        }
      }
      localStorage.setItem(
        LAST_RUN_DRAFT_STORAGE_KEY,
        JSON.stringify({
          userQuery: payload.user_query,
          competitors: payload.competitors,
          domainHint: payload.domain_hint,
          referenceUrls: payload.reference_urls ?? [],
          targetRoles: payload.target_roles,
          reportDepth: payload.report_depth,
        }),
      );
      pushToast({
        title: "分析任务已启动",
        description: "正在进入实时进度页，分析将在后台进行。",
        variant: "success",
      });
      navigate(`/app/runs/${created.run_id}`);
    } catch (error) {
      pushToast({
        title: "创建任务失败",
        description: error instanceof Error ? error.message : "请稍后重试",
        variant: "danger",
      });
    }
  }

  return (
    <section className="space-y-6">
      <header className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className="text-h1 text-foreground">新建竞品分析</h1>
          <IntakeModeSwitcher active="expert" />
        </div>
        <p className="text-caption text-foreground-muted">
          填写核心问题、选择竞品与关注角色后即可启动。不确定竞品时可留空，Agent 会自动发现赛道内的主要竞品。
        </p>
        <div className="flex flex-wrap gap-2">
          {RUN_TEMPLATES.map((template) => (
            <Button
              key={template.id}
              onClick={() => applyTemplate(template)}
              size="sm"
              type="button"
              variant="secondary"
            >
              {template.label}
            </Button>
          ))}
          <Button
            onClick={() => {
              pushToast({
                title: "已加载上次参数",
                description: "如果你之前创建过任务，当前表单已自动填充。",
              });
            }}
            size="sm"
            type="button"
            variant="ghost"
          >
            <CopyPlus className="mr-1.5 h-3.5 w-3.5" />
            克隆上次
          </Button>
        </div>
      </header>

      {competitorSeedsQuery.isLoading ? (
        <div className="space-y-3">
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-48 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      ) : null}

      {competitorSeedsQuery.isError ? (
        <div className="rounded-lg border border-danger/30 bg-danger/5 p-4 text-caption text-danger">竞品样例加载失败：{competitorSeedsQuery.error.message}</div>
      ) : null}

      {!competitorSeedsQuery.isLoading && !competitorSeedsQuery.isError ? (
        <form className="space-y-5" onSubmit={handleSubmit}>
          <Card className="border-primary/30">
            <CardHeader className="pb-3">
              <CardTitle className="inline-flex items-center gap-2 text-lg">
                <Badge>Step 1</Badge>
                <Compass className="h-4 w-4 text-primary" />
                你想分析什么问题？
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="space-y-1">
                <label className="text-sm font-medium">分析档位</label>
                <ReportDepthSelector value={reportDepth} onChange={setReportDepth} />
                <p className="text-xs text-muted-foreground">
                  Quick 适合日常调研；Deep 提升覆盖和深度；Debug 仅在本地开发环境显示。
                </p>
              </div>
              <label className="text-sm font-medium" htmlFor="user-query">
                分析目标
              </label>
              <textarea
                className="min-h-28 w-full rounded-md border border-white/[0.08] bg-white/[0.03] px-3 py-2 text-caption text-foreground outline-none transition focus:border-primary/40 focus:ring-2 focus:ring-ring/40"
                id="user-query"
                onChange={(event) => setUserQuery(event.target.value)}
                placeholder="例如：对比 Cursor、Windsurf 与 TRAE 在功能、定价和用户反馈上的差异，并给出产品决策建议。"
                value={userQuery}
              />
              <p className="text-xs text-muted-foreground">建议写成明确问题，输出质量会明显更高。</p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="inline-flex items-center gap-2 text-lg">
                <Badge>Step 2</Badge>
                <Users className="h-4 w-4 text-primary" />
                选择要对比的竞品
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex flex-col gap-2 sm:flex-row">
                <Input
                  onChange={(event) => setCompetitorInput(event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key !== "Enter") {
                      return;
                    }
                    event.preventDefault();
                    addCompetitor(competitorInput);
                  }}
                  placeholder="输入竞品名，例如 Cursor / Windsurf / TRAE"
                  value={competitorInput}
                />
                <Button onClick={() => addCompetitor(competitorInput)} type="button" variant="outline">
                  添加竞品
                </Button>
              </div>

              <div className="grid gap-2 sm:grid-cols-2">
                {competitorSuggestions.map((item) => (
                  <button
                    aria-label={`添加竞品 ${item.display_name}`}
                    className="rounded-md border border-border/90 bg-background/60 px-3 py-2 text-left text-sm transition hover:border-primary/50 hover:bg-primary/10 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                    key={item.id}
                    onClick={() => addCompetitor(item.display_name)}
                    type="button"
                  >
                    <p className="font-medium">{item.display_name}</p>
                    <p className="text-xs text-muted-foreground">{item.category ?? "通用类竞品"}</p>
                  </button>
                ))}
              </div>

              <div className="space-y-2">
                <p className="text-xs text-muted-foreground">已选择竞品（点击删除）</p>
                <div className="flex flex-wrap gap-2">
                  {selectedCompetitors.map((item) => (
                    <button
                      aria-label={`移除竞品 ${item}`}
                      className="rounded-full border border-primary/45 bg-primary/10 px-3 py-1.5 text-xs text-foreground transition hover:bg-primary/20 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                      key={item}
                      onClick={() => removeCompetitor(item)}
                      type="button"
                    >
                      {item} ×
                    </button>
                  ))}
                </div>
              </div>
              <div className="space-y-2">
                <p className="text-xs text-muted-foreground">批量导入（每行一个竞品）</p>
                <textarea
                  className="min-h-24 w-full rounded-md border border-white/[0.08] bg-white/[0.03] px-3 py-2 text-caption text-foreground outline-none transition focus:border-primary/40 focus:ring-2 focus:ring-ring/40"
                  onChange={(event) => setBulkCompetitorInput(event.target.value)}
                  placeholder={"Cursor\nWindsurf\nTRAE"}
                  value={bulkCompetitorInput}
                />
                <div>
                  <Button onClick={importCompetitorsFromBulkInput} size="sm" type="button" variant="outline">
                    批量导入竞品
                  </Button>
                </div>
              </div>
              {selectedCompetitors.length === 0 && (
                <div className="rounded-md border border-primary/30 bg-primary/5 px-4 py-3 text-sm text-foreground-muted">
                  <p className="font-medium text-primary">赛道扫描模式</p>
                  <p className="mt-1 text-xs">不填写竞品时，Agent 将根据你的问题描述自动搜索并发现该领域的主要竞品，然后进行深度分析。</p>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="inline-flex items-center gap-2 text-lg">
                <Badge>Step 3</Badge>
                <Target className="h-4 w-4 text-primary" />
                你的关注角色
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="grid gap-3 sm:grid-cols-2">
                {ROLE_OPTIONS.map((role) => {
                  const Icon = role.icon;
                  const isSelected = targetRoles.includes(role.id);
                  return (
                    <button
                      aria-label={`切换角色 ${role.label}`}
                      className={`rounded-xl border px-4 py-3 text-left transition ${
                        isSelected
                          ? "border-primary/60 bg-primary/12"
                          : "border-border/90 bg-background/60 hover:border-primary/40"
                      } focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring`}
                      key={role.id}
                      onClick={() => toggleRole(role.id)}
                      type="button"
                    >
                      <div className="mb-2 inline-flex h-8 w-8 items-center justify-center rounded-full bg-muted">
                        <Icon className="h-4 w-4 text-primary" />
                      </div>
                      <p className="font-medium">{role.label}</p>
                      <p className="mt-1 text-xs text-muted-foreground">{role.description}</p>
                    </button>
                  );
                })}
              </div>
            </CardContent>
          </Card>

          <details className="group rounded-xl border border-border/90 bg-card/70 px-4 py-3">
            <summary className="flex cursor-pointer list-none items-center justify-between text-sm font-medium">
              <span className="inline-flex items-center gap-2">
                <Link2 className="h-4 w-4 text-primary" />
                高级选项（可选）
              </span>
              <ChevronDown className="h-4 w-4 text-muted-foreground transition group-open:rotate-180" />
            </summary>
            <div className="mt-4 space-y-4 border-t border-border/80 pt-4">
              <div className="space-y-2">
                <label className="text-sm font-medium" htmlFor="self-product">
                  我方产品 / 定位
                </label>
                <textarea
                  className="min-h-16 w-full rounded-md border border-white/[0.08] bg-white/[0.03] px-3 py-2 text-caption text-foreground outline-none transition focus:border-primary/40 focus:ring-2 focus:ring-ring/40"
                  id="self-product"
                  onChange={(event) => setSelfProduct(event.target.value)}
                  placeholder="例如：我们是字节 TRAE 团队，做 AI 编程 IDE，主打企业内研发提效"
                  value={selfProduct}
                />
                <p className="text-xs text-muted-foreground">
                  填写后结论会「相对我方」给出差距与投入方向，而非中立罗列。
                </p>
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-2">
                  <label className="text-sm font-medium" htmlFor="market-scope">
                    市场 / 地域范围
                  </label>
                  <Input
                    id="market-scope"
                    onChange={(event) => setMarketScope(event.target.value)}
                    placeholder="例如：中国 vs 海外 / 中小企业"
                    value={marketScope}
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium" htmlFor="time-context">
                    时效 / 决策时间
                  </label>
                  <Input
                    id="time-context"
                    onChange={(event) => setTimeContext(event.target.value)}
                    placeholder="例如：下月汇报 / 仅看近一年"
                    value={timeContext}
                  />
                </div>
              </div>

              <div className="space-y-2">
                <label className="text-sm font-medium" htmlFor="domain-hint">
                  领域提示（domain hint）
                </label>
                <textarea
                  className="min-h-20 w-full rounded-md border border-white/[0.08] bg-white/[0.03] px-3 py-2 text-caption text-foreground outline-none transition focus:border-primary/40 focus:ring-2 focus:ring-ring/40"
                  id="domain-hint"
                  onChange={(event) => setDomainHint(event.target.value)}
                  placeholder="例如：协作知识库产品、B2B SaaS、面向企业采购决策"
                  value={domainHint}
                />
                <p className="text-xs text-muted-foreground">可帮助 Agent 更快收敛信息源和分析维度。</p>
              </div>

              <div className="space-y-2">
                <p className="text-sm font-medium">参考 URL</p>
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Input
                    onChange={(event) => setReferenceUrlInput(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key !== "Enter") {
                        return;
                      }
                      event.preventDefault();
                      addReferenceUrl(referenceUrlInput);
                    }}
                    placeholder="https://..."
                    value={referenceUrlInput}
                  />
                  <Button onClick={() => addReferenceUrl(referenceUrlInput)} type="button" variant="outline">
                    添加链接
                  </Button>
                </div>
                <div className="flex flex-wrap gap-2">
                  {referenceUrls.map((item) => (
                    <button
                      aria-label={`删除参考链接 ${item}`}
                      className="rounded-md border border-border px-2 py-1 text-xs text-muted-foreground hover:border-primary hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                      key={item}
                      onClick={() => removeReferenceUrl(item)}
                      type="button"
                    >
                      {item} ×
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </details>

          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="inline-flex items-center gap-2 text-xs text-muted-foreground">
              <Rocket className="h-3.5 w-3.5" />
              预计 1-3 分钟生成首版报告（视数据源质量而定）
            </p>
            <label className="inline-flex items-center gap-2 text-xs text-muted-foreground">
              <input
                checked={addToWatchlist}
                className="h-3.5 w-3.5 accent-primary"
                onChange={(event) => setAddToWatchlist(event.target.checked)}
                type="checkbox"
              />
              将本次竞品加入追踪列表
            </label>
            <Button disabled={!canSubmit} size="lg" type="submit">
              {createRunMutation.isPending ? "启动中..." : "启动分析"}
            </Button>
          </div>
        </form>
      ) : null}
    </section>
  );
}
