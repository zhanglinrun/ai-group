import { memo } from 'react';
import { NavLink } from 'react-router-dom';
import classNames from 'classnames';
import { ROUTES } from '@/router/routes';
import { authApi } from '@/services/auth';
import { useNavigate } from 'react-router-dom';

const navItems = [
  {
    to: ROUTES.CHAT,
    label: '对话',
  },
  {
    to: ROUTES.PRICING,
    label: '购买额度',
  },
  {
    to: ROUTES.GROUP_BUY_HALL,
    label: '拼团大厅',
  },
  {
    to: `${ROUTES.PRICING}?tab=orders`,
    label: '订单',
  },
  {
    to: ROUTES.ACCOUNT,
    label: '额度中心',
  },
];

const ShellNav = memo(() => {
  const navigate = useNavigate();

  const handleLogout = () => {
    void authApi.logout().then(() => {
      navigate(ROUTES.LOGIN, { replace: true });
    });
  };

  return (
    <header className="border-b border-[var(--chat-border)] bg-[var(--chat-surface)]/90 px-4 py-3 backdrop-blur-md sm:px-6">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4">
        <div className="text-sm font-semibold text-[var(--chat-text)]">AI Group</div>
        <nav className="flex flex-wrap items-center gap-2">
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                classNames(
                  'rounded-full px-3 py-1.5 text-sm transition-colors',
                  isActive
                    ? 'bg-[var(--primary)] text-white'
                    : 'text-[var(--chat-text-soft)] hover:bg-[var(--secondary)] hover:text-[var(--chat-text)]',
                )
              }
            >
              {item.label}
            </NavLink>
          ))}
          <button
            type="button"
            onClick={handleLogout}
            className="rounded-full px-3 py-1.5 text-sm text-[var(--chat-text-soft)] transition-colors hover:bg-[var(--secondary)] hover:text-[var(--chat-text)]"
          >
            退出
          </button>
        </nav>
      </div>
    </header>
  );
});

ShellNav.displayName = 'ShellNav';

export default ShellNav;
