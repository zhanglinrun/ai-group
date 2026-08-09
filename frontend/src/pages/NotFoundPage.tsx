import { Link } from "react-router-dom";

export function NotFoundPage(): JSX.Element {
  return (
    <section className="space-y-3">
      <h1 className="text-2xl font-semibold">页面不存在</h1>
      <p className="text-sm text-muted-foreground">你访问的路径没有对应页面。</p>
      <Link className="text-sm text-primary underline-offset-4 hover:underline" to="/">
        返回首页
      </Link>
    </section>
  );
}
