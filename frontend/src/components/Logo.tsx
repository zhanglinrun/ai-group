import { cn } from "@/lib/utils";

interface LogoProps {
  className?: string;
  size?: "sm" | "md";
}


export function Logo({ className, size = "md" }: LogoProps): JSX.Element {
  return (
    <div className={cn("flex items-center gap-2", className)}>
      <img
        src="/bear-doctor-logo.png"
        alt="熊博士agent"
        className={cn("rounded-lg object-contain", size === "sm" ? "h-7 w-7" : "h-9 w-9")}
      />
      <span className={cn("font-semibold tracking-tight text-foreground", size === "sm" ? "text-caption" : "text-body")}>
        熊博士agent
      </span>
    </div>
  );
}
