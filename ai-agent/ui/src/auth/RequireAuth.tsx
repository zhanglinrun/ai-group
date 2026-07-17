import { Navigate, useLocation } from 'react-router-dom';
import { isAuthenticated } from './token';
import { ROUTES } from '@/router/routes';

type RequireAuthProps = {
  children: React.ReactNode;
};

export default function RequireAuth(props: RequireAuthProps) {
  const location = useLocation();

  if (!isAuthenticated()) {
    const from = `${location.pathname}${location.search}${location.hash}`;
    return <Navigate to={ROUTES.LOGIN} replace state={{ from }} />;
  }

  return <>{props.children}</>;
}
