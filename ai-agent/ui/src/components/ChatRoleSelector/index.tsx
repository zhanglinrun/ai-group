import { CheckIcon, ChevronDownIcon, UserRoundIcon } from 'lucide-react';

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';

type Props = {
  roles: CHAT.FixRole[];
  selectedRole?: CHAT.ConversationRole | null;
  disabled?: boolean;
  onSelect: (role: CHAT.FixRole) => void;
};

const ChatRoleSelector: ReactorType.FC<Props> = ({ roles, selectedRole, disabled, onSelect }) => {
  const hasRoles = roles.length > 0;
  const label = selectedRole?.agentName || (hasRoles ? roles[0].agentName : '暂无角色');
  const unavailable = selectedRole?.available === false;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          disabled={disabled || !hasRoles}
          className={cn(
            'group inline-flex h-9 max-w-full items-center gap-2 rounded-full border border-transparent px-3 text-[14px] font-medium transition-all duration-200',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
            disabled || !hasRoles
              ? 'cursor-not-allowed opacity-50'
              : 'hover:bg-[var(--chat-surface-soft)]',
            unavailable
              ? 'bg-[color-mix(in_oklab,var(--destructive)_14%,transparent)] text-[var(--destructive)]'
              : 'bg-transparent text-[var(--chat-text)]',
          )}
        >
          <span
            className={cn(
              'flex size-[26px] shrink-0 items-center justify-center rounded-full transition-all duration-200',
              unavailable
                ? 'bg-[color-mix(in_oklab,var(--destructive)_18%,transparent)] text-[var(--destructive)]'
                : 'bg-brand-soft text-brand',
            )}
          >
            <UserRoundIcon className="size-4" />
          </span>
          <span className="max-w-[120px] truncate">{label}</span>
          <ChevronDownIcon className="size-4 shrink-0 text-[var(--chat-text-muted)]" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        side="bottom"
        sideOffset={12}
        className="w-[240px] rounded-[16px] border border-border bg-popover p-1 shadow-[0_10px_28px_-18px_rgba(15,23,42,0.2)]"
      >
        <div className="px-2 pb-1 pt-0.5 text-[10px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
          角色库
        </div>
        <div className="space-y-1">
          {roles.map((role) => {
            const active = role.agentId === selectedRole?.agentId;
            return (
              <button
                key={role.agentId}
                type="button"
                className={cn(
                  'flex w-full gap-2 rounded-xl border border-transparent px-2 py-2 text-left transition-all duration-200',
                  active ? 'bg-brand-soft' : 'bg-transparent hover:bg-[var(--chat-surface-soft)]',
                )}
                onClick={() => onSelect(role)}
              >
                <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-lg bg-brand-soft text-brand">
                  <UserRoundIcon className="size-3.5" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[14px] font-medium text-[var(--chat-text)]">
                    {role.agentName}
                  </span>
                  <span className="mt-0.5 line-clamp-2 text-[11px] leading-4 text-[var(--chat-text-soft)]">
                    {role.description || 'Fix 模式角色'}
                  </span>
                </span>
                {active ? <CheckIcon className="mt-1 size-3 shrink-0 text-brand" /> : null}
              </button>
            );
          })}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ChatRoleSelector;
