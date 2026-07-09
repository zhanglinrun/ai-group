import { Navigate, useSearchParams } from "react-router-dom";
import { ROUTES } from "@/router/routes";

/** Legacy /orders route → unified trade workspace orders tab */
export default function OrdersPage() {
  const [searchParams] = useSearchParams();
  const payment = searchParams.get("payment");
  const params = new URLSearchParams({ tab: "orders" });
  if (payment === "success") {
    params.set("payment", "success");
  }
  return <Navigate to={`${ROUTES.PRICING}?${params.toString()}`} replace />;
}
