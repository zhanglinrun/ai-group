import { memo } from 'react';
import { Monitor, Moon, Sun } from 'lucide-react';
import classNames from 'classnames';
import { useTheme } from './themeRuntime';
import { FONT_SCALES, MODES, PALETTES, type ThemeMode } from './palettes';

const MODE_ICONS: Record<ThemeMode, React.ComponentType<{ className?: string }>> = {
  light: Sun,
  system: Monitor,
  dark: Moon,
};

const FONT_SCALE_PREVIEW: Record<string, string> = {
  sm: 'text-[13px]',
  base: 'text-[15px]',
  md: 'text-[17px]',
  lg: 'text-[19px]',
};

/** Preview swatch cluster echoing the palette's signature accent. */
const PaletteSwatch = memo(({ brand }: { brand: string }) => (
  <div className="flex items-end gap-0.5">
    <span className="h-4 w-1.5 rounded-full bg-[var(--chat-text)]" />
    <span className="h-5 w-1.5 rounded-full bg-[var(--chat-text-soft)]" />
    <span className="h-6 w-2 rounded-full" style={{ backgroundColor: brand }} />
    <span className="h-4 w-1.5 rounded-full bg-[var(--chat-surface-muted)]" />
    <span className="h-3 w-1.5 rounded-full bg-[var(--chat-border)]" />
  </div>
));
PaletteSwatch.displayName = 'PaletteSwatch';

type AppearanceSettingsProps = {
  className?: string;
  /** Hide the section heading when embedded under an existing title. */
  hideHeading?: boolean;
};

const AppearanceSettings = memo(({ className, hideHeading }: AppearanceSettingsProps) => {
  const { palette, mode, fontScale, resolvedMode, setPalette, setMode, setFontScale } = useTheme();

  return (
    <section
      className={classNames(
        'rounded-3xl border border-[var(--chat-border)] bg-[var(--chat-surface)]/90 p-6 shadow-[var(--shadow-sm)]',
        className,
      )}
    >
      {!hideHeading ? <div className="text-base font-medium">外观</div> : null}

      {/* 主题色板 */}
      <div className={classNames(!hideHeading && 'mt-4')}>
        <div className="mb-2 text-xs font-medium text-[var(--chat-text-soft)]">主题</div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {PALETTES.map((item) => {
            const active = item.id === palette;
            const previewBrand = resolvedMode === 'dark' ? item.brandDark : item.brand;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setPalette(item.id)}
                aria-pressed={active}
                className={classNames(
                  'flex flex-col gap-4 rounded-2xl border p-3 text-left transition-all',
                  active
                    ? 'border-brand ring-2 ring-brand/40'
                    : 'border-[var(--chat-border)] hover:border-[var(--chat-border-strong)]',
                )}
              >
                <div className="flex items-start justify-between">
                  <span className="text-[10px] uppercase tracking-wide text-[var(--chat-text-muted)]">
                    {item.category}
                  </span>
                  <PaletteSwatch brand={previewBrand} />
                </div>
                <span className="text-sm font-semibold">{item.label}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* 颜色模式 */}
      <div className="mt-6">
        <div className="mb-2 text-xs font-medium text-[var(--chat-text-soft)]">颜色模式</div>
        <div className="grid grid-cols-3 gap-3">
          {MODES.map((item) => {
            const active = item.id === mode;
            const Icon = MODE_ICONS[item.id];
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setMode(item.id)}
                aria-pressed={active}
                className={classNames(
                  'flex items-center justify-center gap-2 rounded-xl border px-3 py-3 text-sm transition-all',
                  active
                    ? 'border-brand bg-brand-soft text-brand'
                    : 'border-[var(--chat-border)] text-[var(--chat-text-soft)] hover:border-[var(--chat-border-strong)]',
                )}
              >
                <Icon className="h-4 w-4" />
                <span>{item.label}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* 字体大小 */}
      <div className="mt-6">
        <div className="mb-2 text-xs font-medium text-[var(--chat-text-soft)]">字体大小</div>
        <div className="grid grid-cols-4 gap-3">
          {FONT_SCALES.map((item) => {
            const active = item.id === fontScale;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => setFontScale(item.id)}
                aria-pressed={active}
                className={classNames(
                  'flex flex-col items-center justify-center gap-1 rounded-xl border px-2 py-3 transition-all',
                  active
                    ? 'border-brand bg-brand-soft text-brand'
                    : 'border-[var(--chat-border)] text-[var(--chat-text-soft)] hover:border-[var(--chat-border-strong)]',
                )}
              >
                <span className="text-xs">{item.label}</span>
                <span
                  className={classNames(
                    'font-[family-name:var(--font-display)]',
                    FONT_SCALE_PREVIEW[item.id],
                  )}
                >
                  Aa
                </span>
              </button>
            );
          })}
        </div>
      </div>
    </section>
  );
});

AppearanceSettings.displayName = 'AppearanceSettings';

export default AppearanceSettings;
