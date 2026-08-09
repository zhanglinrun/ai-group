import {
  FolderClock,
  FolderKanban,
  Plus,
  Settings2,
  ShoppingCart,
  WalletCards,
  ShieldCheck,
  LogOut,
} from "lucide-react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";

import { useSkillCandidates } from "@/api/hooks";
import { Badge } from "@/components/ui/badge";
import { Logo } from "@/components/Logo";
import { cn } from "@/lib/utils";
import { logout } from "@/platform/client";

const INTAKE_SESSION_KEY = "xiongdoctor.intake.run_id";

interface NavItem {
  to: string;
  icon: typeof FolderKanban;
  label: string;
  end: boolean;
  /**
   * Optional matcher that lights up the item even when the active URL is not
   * a literal prefix of `to`. Used so /app/runs/:id (run detail variants)
   * keep "我的分析" highlighted — without this they look orphaned in the UI.
   */
  matchPath?: (pathname: string) => boolean;
}

const NAV_ITEMS: readonly NavItem[] = [
  {
    to: "/app",
    icon: FolderKanban,
    label: "我的分析",
    end: true,
    // /app/runs/new* belongs to the "新建分析" tab, so we explicitly exclude
    // it; everything else under /app/runs/* (detail/live/plan/trace/evidence)
    // anchors back to "我的分析".
    matchPath: (pathname) =>
      pathname.startsWith("/app/runs/") && !pathname.startsWith("/app/runs/new"),
  },
  { to: "/app/runs/new", icon: Plus, label: "新建分析", end: false },
  { to: "/app/watch", icon: FolderClock, label: "竞品追踪", end: false },
  { to: "/group-buy", icon: ShoppingCart, label: "拼团商城", end: false },
  { to: "/account", icon: WalletCards, label: "积分账户", end: false },
  { to: "/orders", icon: WalletCards, label: "我的订单", end: false },
  { to: "/admin", icon: ShieldCheck, label: "管理中心", end: false },
];

export function WorkspaceShell(): JSX.Element {
  const pendingCandidatesQuery = useSkillCandidates(
    {
      status: "staging",
      limit: 1,
      offset: 0,
    },
    { errorToast: false },
  );
  const pendingCount = pendingCandidatesQuery.data?.total ?? 0;
  const location = useLocation();
  const navigate = useNavigate();

  return (
    <div className="flex h-screen overflow-hidden bg-background text-foreground">
      {/* Sidebar */}
      <aside className="flex w-56 shrink-0 flex-col border-r border-border bg-page">
        {/* Brand */}
        <div className="flex h-14 items-center px-4">
          <NavLink to="/app">
            <Logo size="sm" />
          </NavLink>
        </div>

        {/* Main nav */}
        <nav className="flex-1 space-y-0.5 px-2 py-2">
          {NAV_ITEMS.map((item) => {
            const matchedExternally = item.matchPath?.(location.pathname) ?? false;
            const isNewRunEntry = item.to === "/app/runs/new";
            const isOnNewRunFlow = location.pathname.startsWith("/app/runs/new");
            return (
              <NavLink
                key={item.to}
                end={item.end}
                className={({ isActive }) =>
                  cn(
                    "flex items-center gap-2.5 rounded-md px-3 py-2 text-caption font-medium text-foreground-muted transition-colors",
                    "hover:bg-secondary hover:text-foreground",
                    (isActive || matchedExternally) && "bg-secondary text-foreground",
                  )
                }
                to={item.to}
                onClick={(event) => {
                  if (!isNewRunEntry) {
                    return;
                  }
                  const savedRunId = sessionStorage.getItem(INTAKE_SESSION_KEY);
                  const hasPendingIntake = typeof savedRunId === "string" && savedRunId.length > 0;
                  if (!hasPendingIntake) {
                    return;
                  }
                  const wantsFreshSession = window.confirm(
                    "检测到你有一个未完成的新建分析会话。确定要放弃当前会话并新开任务吗？\n\n点击「取消」将继续当前会话。",
                  );
                  if (!wantsFreshSession) {
                    if (isOnNewRunFlow) {
                      event.preventDefault();
                    }
                    return;
                  }
                  event.preventDefault();
                  navigate(`/app/runs/new?fresh=${Date.now().toString(10)}`);
                }}
              >
                <item.icon className="h-4 w-4 shrink-0" />
                {item.label}
              </NavLink>
            );
          })}
        </nav>

        {/* Bottom section */}
        <div className="border-t border-border p-2">
          <NavLink
            className={({ isActive }) =>
              cn(
                "flex items-center gap-2.5 rounded-md px-3 py-2 text-caption font-medium text-foreground-muted transition-colors",
                "hover:bg-secondary hover:text-foreground",
                isActive && "bg-secondary text-foreground",
              )
            }
            to="/app/settings/skill-admin"
          >
            <Settings2 className="h-4 w-4 shrink-0" />
            设置
            {pendingCount > 0 && (
              <Badge variant="default" className="ml-auto">
                {pendingCount}
              </Badge>
            )}
          </NavLink>
          <button
            type="button"
            className="mt-1 flex w-full items-center gap-2.5 rounded-md px-3 py-2 text-caption font-medium text-foreground-muted transition-colors hover:bg-danger/10 hover:text-danger"
            onClick={() => { void logout().finally(() => navigate("/login", { replace: true })); }}
          >
            <LogOut className="h-4 w-4 shrink-0" />
            退出登录
          </button>
        </div>
      </aside>

      {/* Main content */}
      {/*
        Why flex column + flex-1 on the inner wrapper:
        chat-style pages need a real height edge so their internal
        `flex-1 overflow-y-auto` actually scrolls instead of letting the
        message list push the whole page taller. Other pages can still
        scroll the main column when content exceeds viewport.
      */}
      <main className="flex min-h-0 flex-1 flex-col overflow-y-auto">
        <div className="mx-auto flex min-h-0 w-full max-w-5xl flex-1 flex-col px-8 py-6">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
