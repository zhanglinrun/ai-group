import React, { Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import Layout from '@/layout/index';
import RequireAuth from '@/auth/RequireAuth';
import { Loading } from '@/components';
import { ROUTES } from './routes';

const Home = React.lazy(() => import('@/pages/Home'));
const Login = React.lazy(() => import('@/pages/Login'));
const Register = React.lazy(() => import('@/pages/Register'));
const Pricing = React.lazy(() => import('@/pages/Pricing'));
const GroupBuyHall = React.lazy(() => import('@/pages/GroupBuyHall'));
const GroupBuy = React.lazy(() => import('@/pages/GroupBuy'));
const Orders = React.lazy(() => import('@/pages/Orders'));
const Account = React.lazy(() => import('@/pages/Account'));
const WorkspaceMRag = React.lazy(() => import('@/pages/WorkspaceMRag'));
const WorkspaceImageGeneration = React.lazy(() => import('@/pages/WorkspaceImageGeneration'));
const Admin = React.lazy(() => import('@/pages/Admin'));
const NotFound = React.lazy(() => import('@/components/NotFound'));

const withSuspense = (element: React.ReactNode) => (
  <Suspense fallback={<Loading loading={true} className="h-full"/>}>
    {element}
  </Suspense>
);

const withAuth = (element: React.ReactNode) => (
  <RequireAuth>{withSuspense(element)}</RequireAuth>
);

const router = createBrowserRouter([
  {
    path: ROUTES.LOGIN,
    element: withSuspense(<Login />),
  },
  {
    path: ROUTES.REGISTER,
    element: withSuspense(<Register />),
  },
  {
    // 运营端：独立外壳（自带管理员登录门），不进用户端 Layout
    path: ROUTES.ADMIN,
    element: withSuspense(<Admin />),
  },
  {
    path: ROUTES.HOME,
    element: <Layout />,
    children: [
      {
        index: true,
        element: <Navigate to={ROUTES.CHAT} replace />,
      },
      {
        path: ROUTES.CHAT.slice(1),
        element: withAuth(<Home />),
      },
      {
        path: ROUTES.PRICING.slice(1),
        element: withAuth(<Pricing />),
      },
      {
        path: ROUTES.GROUP_BUY_HALL.slice(1),
        element: withAuth(<GroupBuyHall />),
      },
      {
        path: `${ROUTES.GROUP_BUY_HALL.slice(1)}/:activityId`,
        element: withAuth(<GroupBuy />),
      },
      {
        path: ROUTES.ORDERS.slice(1),
        element: withAuth(<Orders />),
      },
      {
        path: ROUTES.ACCOUNT.slice(1),
        element: withAuth(<Account />),
      },
      {
        path: ROUTES.WORKSPACE.slice(1),
        element: <Navigate to={ROUTES.WORKSPACE_MRAG} replace />,
      },
      {
        path: ROUTES.WORKSPACE_MRAG.slice(1),
        element: withAuth(<WorkspaceMRag />),
      },
      {
        path: ROUTES.WORKSPACE_IMAGE_GENERATION.slice(1),
        element: withAuth(<WorkspaceImageGeneration />),
      },
      {
        path: ROUTES.NOT_FOUND,
        element: withSuspense(<NotFound />),
      },
    ],
  },
  {
    path: '*',
    element: <Navigate to={ROUTES.NOT_FOUND} replace />,
  },
]);

export default router;
