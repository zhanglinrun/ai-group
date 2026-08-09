import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";

import { Logo } from "@/components/Logo";
import { cn } from "@/lib/utils";
import { currentUser, type PlatformUser } from "@/platform/client";

export function MarketingShell(): JSX.Element {
  const [user, setUser] = useState<PlatformUser | null>(null);

  useEffect(() => {
    let active = true;
    currentUser()
      .then((value) => {
        if (active) setUser(value);
      })
      .catch(() => {
        if (active) setUser(null);
      });
    return () => {
      active = false;
    };
  }, []);

  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="sticky top-0 z-40 border-b border-border bg-page/90 backdrop-blur-xl">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
          <NavLink to="/">
            <Logo size="sm" />
          </NavLink>
          <nav className="flex items-center gap-1 text-caption">
            <NavLink
              className={({ isActive }) =>
                cn(
                  "rounded-md px-3 py-1.5 text-foreground-muted transition-colors hover:text-foreground",
                  isActive && "text-foreground",
                )
              }
              to="/examples"
            >
              案例库
            </NavLink>
            <NavLink
              className={({ isActive }) =>
                cn(
                  "rounded-md px-3 py-1.5 text-foreground-muted transition-colors hover:text-foreground",
                  isActive && "text-foreground",
                )
              }
              to="/pricing"
            >
              产品与价格
            </NavLink>
            <NavLink
              className={({ isActive }) => cn("rounded-md px-3 py-1.5 text-foreground-muted transition-colors hover:text-foreground", isActive && "text-foreground")}
              to="/group-buy"
            >
              拼团商城
            </NavLink>
            {user ? (
              <span className="ml-2 rounded-md bg-primary/10 px-3.5 py-1.5 text-primary">{user.username}</span>
            ) : (
              <NavLink className="ml-2 rounded-md bg-primary px-3.5 py-1.5 text-primary-foreground transition-colors hover:bg-primary/90" to="/login">
                登录
              </NavLink>
            )}
            <NavLink
              className="rounded-md border border-border bg-surface px-3.5 py-1.5 text-foreground transition-colors hover:bg-raised"
              to="/app"
            >
              进入工作区
            </NavLink>
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl px-6 py-section">
        <Outlet />
      </main>
    </div>
  );
}
