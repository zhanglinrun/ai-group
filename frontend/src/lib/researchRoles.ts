import type { UserRole } from "@/api/types";

const USER_ROLE_LABELS: Record<UserRole, string> = {
  researcher: "学术研究",
  engineer: "工程研发",
  pm: "产品与竞品决策",
  founder: "赛道与创业判断",
  sales: "销售与商务对标",
  investor: "投资与行业研判",
};

export function researchRoleLabel(role: UserRole): string {
  return USER_ROLE_LABELS[role];
}
