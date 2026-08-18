import { FormEvent, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { login } from "@/platform/client";

export function LoginPage(): JSX.Element {
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(username, password);
      const target = new URLSearchParams(location.search).get("redirect") || "/app";
      navigate(target, { replace: true });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "登录失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-page px-6 py-12">
      <Card className="w-full max-w-md shadow-raised">
        <CardHeader className="space-y-4">
          <Link to="/" className="w-fit"><Logo /></Link>
          <CardTitle>登录熊博士</CardTitle>
          <p className="text-sm text-foreground-muted">登录后可以购买积分、发起深度调研并查看完整账单。</p>
        </CardHeader>
        <CardContent>
          <form className="space-y-4" onSubmit={submit}>
            <Input required minLength={3} placeholder="用户名" value={username} onChange={(event) => setUsername(event.target.value)} />
            <Input required type="password" minLength={8} placeholder="密码" value={password} onChange={(event) => setPassword(event.target.value)} />
            {error ? <p className="rounded-md bg-danger/10 px-3 py-2 text-sm text-danger">{error}</p> : null}
            <Button className="w-full" disabled={submitting}>{submitting ? "登录中…" : "登录"}</Button>
          </form>
          <p className="mt-5 text-center text-sm text-foreground-muted">还没有账号？ <Link className="text-primary hover:underline" to="/register">立即注册</Link></p>
        </CardContent>
      </Card>
    </div>
  );
}
