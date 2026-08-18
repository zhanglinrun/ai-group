import { createBrowserRouter, Navigate, redirect } from "react-router-dom";

import { MarketingShell } from "@/app/layout/MarketingShell";
import { WorkspaceShell } from "@/app/layout/WorkspaceShell";
import { RequireAuth } from "@/platform/RequireAuth";
import { RequireAdmin } from "@/platform/RequireAdmin";
import { SHOW_DEBUG_PANELS } from "@/lib/debugFlags";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { LoginPage } from "@/pages/auth/LoginPage";
import { RegisterPage } from "@/pages/auth/RegisterPage";
import { AccountPage } from "@/pages/commerce/AccountPage";
import { GroupHallPage } from "@/pages/commerce/GroupHallPage";
import { OrdersPage } from "@/pages/commerce/OrdersPage";
import { CheckoutPage } from "@/pages/commerce/CheckoutPage";
import { PaymentResultPage } from "@/pages/commerce/PaymentResultPage";
import { AdminPage } from "@/pages/admin/AdminPage";
import { RouteErrorPage } from "@/app/RouteErrorPage";

const WORKSPACE_CHILDREN = [
  {
    index: true,
    lazy: async () => {
      const module = await import("@/pages/app/DashboardPage");
      return { Component: module.DashboardPage };
    },
  },
  {
    path: "runs/new",
    lazy: async () => {
      const module = await import("@/pages/NewRunChatPage");
      return { Component: module.NewRunChatPage };
    },
  },
  {
    path: "runs/new/expert",
    lazy: async () => {
      const module = await import("@/pages/NewRunPage");
      return { Component: module.NewRunPage };
    },
  },
  {
    path: "runs/:runId",
    lazy: async () => {
      const module = await import("@/pages/RunViewPage");
      return { Component: module.RunViewPage };
    },
  },
  {
    path: "runs/:runId/plan",
    lazy: async () => {
      const module = await import("@/pages/PlanConfirmPage");
      return { Component: module.PlanConfirmPage };
    },
  },
  {
    path: "runs/:runId/live",
    lazy: async () => {
      const module = await import("@/pages/LiveRunPage");
      return { Component: module.LiveRunPage };
    },
  },
  {
    path: "runs/:runId/trace",
    lazy: async () => {
      const module = await import("@/pages/RunTracePage");
      return { Component: module.RunTracePage };
    },
  },
  {
    path: "runs/:runId/audit",
    ...(SHOW_DEBUG_PANELS
      ? {
          lazy: async () => {
            const module = await import("@/pages/RunAuditPage");
            return { Component: module.RunAuditPage };
          },
        }
      : { element: <NotFoundPage /> }),
  },
  {
    path: "runs/:runId/evidence",
    lazy: async () => {
      const module = await import("@/pages/RunEvidencePage");
      return { Component: module.RunEvidencePage };
    },
  },
  {
    path: "watch",
    lazy: async () => {
      const module = await import("@/pages/app/WatchPage");
      return { Component: module.WatchPage };
    },
  },
  {
    path: "settings",
    lazy: async () => {
      const module = await import("@/pages/app/SettingsPage");
      return { Component: module.SettingsPage };
    },
  },
  {
    path: "settings/skill-admin",
    lazy: async () => {
      const module = await import("@/pages/SkillStagingPage");
      return { Component: module.SkillStagingPage };
    },
  },
  {
    path: "*",
    element: <NotFoundPage />,
  },
] as const;

export const appRouter = createBrowserRouter([
  {
    errorElement: <RouteErrorPage />,
    children: [
      {
        path: "/",
        element: <MarketingShell />,
        children: [
          {
            index: true,
            lazy: async () => {
              const module = await import("@/pages/marketing/LandingPage");
              return { Component: module.LandingPage };
            },
          },
          {
            path: "pricing",
            lazy: async () => {
              const module = await import("@/pages/marketing/PricingPage");
              return { Component: module.PricingPage };
            },
          },
          {
            path: "examples",
            element: <RequireAuth />,
            children: [
              {
                index: true,
                lazy: async () => {
                  const module = await import("@/pages/marketing/ExamplesPage");
                  return { Component: module.ExamplesPage };
                },
              },
            ],
          },
          {
            path: "share/:runId",
            lazy: async () => {
              const module = await import("@/pages/marketing/SharedReportPage");
              return { Component: module.SharedReportPage };
            },
          },
          {
            path: "*",
            element: <NotFoundPage />,
          },
        ],
      },
      {
        path: "/app",
        element: <RequireAuth />,
        children: [{ element: <WorkspaceShell />, children: [...WORKSPACE_CHILDREN] }],
      },
      { path: "/login", element: <LoginPage /> },
      { path: "/register", element: <RegisterPage /> },
      // Commerce pages deliberately live inside the same workspace shell as the
      // analysis pages.  Keeping the shell in the route tree (instead of rendering
      // each page in isolation) preserves the left navigation and gives every
      // payment/detail screen a stable way back to the workspace.
      {
        path: "/group-buy",
        element: <RequireAuth />,
        children: [{ element: <WorkspaceShell />, children: [{ index: true, element: <GroupHallPage /> }] }],
      },
      {
        path: "/account",
        element: <RequireAuth />,
        children: [{ element: <WorkspaceShell />, children: [{ index: true, element: <AccountPage /> }] }],
      },
      {
        path: "/orders",
        element: <RequireAuth />,
        children: [{ element: <WorkspaceShell />, children: [{ index: true, element: <OrdersPage /> }] }],
      },
      {
        path: "/checkout/:orderId",
        element: <RequireAuth />,
        children: [{ element: <WorkspaceShell />, children: [{ index: true, element: <CheckoutPage /> }] }],
      },
      {
        path: "/payment/result",
        element: <RequireAuth />,
        children: [{ element: <WorkspaceShell />, children: [{ index: true, element: <PaymentResultPage /> }] }],
      },
      {
        path: "/admin",
        element: <RequireAdmin />,
        children: [{ element: <WorkspaceShell />, children: [{ index: true, element: <AdminPage /> }] }],
      },
      {
        path: "/runs/new",
        element: <Navigate replace to="/app/runs/new" />,
      },
      {
        path: "/runs/:runId",
        loader: ({ params }) => redirect(`/app/runs/${params.runId ?? ""}`),
      },
      {
        path: "/runs/:runId/trace",
        loader: ({ params }) => redirect(`/app/runs/${params.runId ?? ""}/trace`),
      },
      {
        path: "/runs/:runId/audit",
        loader: ({ params }) => redirect(`/app/runs/${params.runId ?? ""}/audit`),
      },
      {
        path: "/runs/:runId/evidence",
        loader: ({ params }) => redirect(`/app/runs/${params.runId ?? ""}/evidence`),
      },
      {
        path: "/skills/staging",
        element: <Navigate replace to="/app/settings/skill-admin" />,
      },
    ],
  },
]);
