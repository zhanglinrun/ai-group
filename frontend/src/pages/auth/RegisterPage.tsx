import { FormEvent, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { Logo } from "@/components/Logo";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { login, register } from "@/platform/client";

export function RegisterPage(): JSX.Element {
  const navigate = useNavigate();
  const [form, setForm] = useState({ username: "", password: "", confirm: "", email: "" });
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (form.password !== form.confirm) {
      setError("两次密码不一致");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await register({ username: form.username, password: form.password, email: form.email || undefined });
      await login(form.username, form.password);
      navigate("/app", { replace: true });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "注册失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-page px-6 py-12">
      <Card className="w-full max-w-md shadow-raised">
        <CardHeader className="space-y-4"><Link to="/" className="w-fit"><Logo /></Link><CardTitle>创建熊博士账号</CardTitle></CardHeader>
        <CardContent>
          <form className="space-y-4" onSubmit={submit}>
            <Input required minLength={3} placeholder="用户名" value={form.username} onChange={(event) => setForm({ ...form, username: event.target.value })} />
            <Input required type="email" placeholder="邮箱（可选）" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} />
            <Input required minLength={8} type="password" placeholder="密码（至少 8 位）" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} />
            <Input required minLength={8} type="password" placeholder="确认密码" value={form.confirm} onChange={(event) => setForm({ ...form, confirm: event.target.value })} />
            {error ? <p className="rounded-md bg-danger/10 px-3 py-2 text-sm text-danger">{error}</p> : null}
            <Button className="w-full" disabled={submitting}>{submitting ? "创建中…" : "注册并开始"}</Button>
          </form>
          <p className="mt-5 text-center text-sm text-foreground-muted">已有账号？ <Link className="text-primary hover:underline" to="/login">返回登录</Link></p>
        </CardContent>
      </Card>
    </div>
  );
}
