import { CheckCircle2, ListChecks, LoaderCircle, TriangleAlert } from 'lucide-react';

const VerificationCard = ({ verification }: { verification: CHAT.VerificationState }) => {
  const running = verification.status === 'running';
  const passed = verification.status === 'passed';
  const Icon = running ? LoaderCircle : passed ? CheckCircle2 : TriangleAlert;
  // 这是面向用户的运行反馈，不把内部 completion/verification 协议直接暴露出来。
  const title = running ? '正在整理结果' : passed ? '运行结果' : '需要补充结果';
  const tone = passed
    ? 'border-emerald-200 bg-emerald-50/70 text-emerald-800'
    : running
      ? 'border-sky-200 bg-sky-50/70 text-sky-800'
      : 'border-amber-200 bg-amber-50/75 text-amber-900';

  return (
    <section className={`mt-4 rounded-2xl border px-4 py-3 ${tone}`} aria-label="verification-card">
      <div className="flex items-start gap-2.5">
        <Icon className={`mt-0.5 h-4 w-4 shrink-0 ${running ? 'animate-spin' : ''}`} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between gap-2">
            <p className="text-[13px] font-semibold">{title}</p>
            {verification.attempt ? (
              <span className="text-[11px] opacity-70">第 {verification.attempt} 轮</span>
            ) : null}
          </div>
          {verification.summary ? (
            <p className="mt-1 text-[13px] leading-relaxed opacity-90">{verification.summary}</p>
          ) : null}
          {verification.missingRequirements?.length ? (
            <div className="mt-2">
              <p className="flex items-center gap-1 text-[11px] font-medium">
                <TriangleAlert className="h-3 w-3" /> 待补充
              </p>
              <ul className="mt-1 list-disc space-y-0.5 pl-5 text-[11px] leading-relaxed">
                {verification.missingRequirements.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          ) : null}
          {verification.requiredActions?.length ? (
            <div className="mt-2">
              <p className="flex items-center gap-1 text-[11px] font-medium">
                <ListChecks className="h-3 w-3" /> 下一步
              </p>
              <ul className="mt-1 list-disc space-y-0.5 pl-5 text-[11px] leading-relaxed">
                {verification.requiredActions.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      </div>
    </section>
  );
};

export default VerificationCard;
