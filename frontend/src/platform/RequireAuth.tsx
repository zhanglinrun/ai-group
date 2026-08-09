import { useEffect, useState } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";

import { currentUser, type PlatformUser } from "@/platform/client";

export function RequireAuth(): JSX.Element {
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
    return <div className="flex min-h-screen items-center justify-center text-sm text-foreground-muted">正在验证熊博士会话…</div>;
  }
  if (!user) {
    return <Navigate replace to={`/login?redirect=${encodeURIComponent(location.pathname)}`} />;
  }
  return <Outlet />;
}
