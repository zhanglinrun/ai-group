import React from "react";
import ReactDOM from "react-dom/client";
import { RouterProvider } from "react-router-dom";

import { appRouter } from "@/app/router";
import { AppErrorBoundary } from "@/app/AppErrorBoundary";
import { queryClient } from "@/api/queryClient";
import { Toaster } from "@/components/ui/toaster";
import { QueryClientProvider } from "@tanstack/react-query";
import "@/index.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <AppErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <RouterProvider
          fallbackElement={<div className="p-6 text-sm text-muted-foreground">页面加载中...</div>}
          router={appRouter}
        />
        <Toaster />
      </QueryClientProvider>
    </AppErrorBoundary>
  </React.StrictMode>,
);
