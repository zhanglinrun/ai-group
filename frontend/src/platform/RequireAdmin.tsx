import { useEffect, useState } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";

import { currentUser, isAdmin, type PlatformUser } from "@/platform/client";

/** Separate role gate for operator routes; a logged-in USER is not enough. */
export function RequireAdmin(): JSX.Element {
  const location = useLocation();
  const [user, setUser] = useState<PlatformUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    currentUser()
      .then((value) => {
        if (active) setUser(value);
      })
      .catch(() => {
        if (active) setUser(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  if (loading) {
    return <div className="flex min-h-screen items-center justify-center text-sm text-foreground-muted">正在验证管理员权限…</div>;
  }
  if (!user) {
    return <Navigate replace to={`/login?redirect=${encodeURIComponent(location.pathname)}`} />;
  }
  if (!isAdmin(user)) {
    return <Navigate replace to="/app" />;
  }
  return <Outlet />;
}
