import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { ConfigProvider } from 'antd';
import {
  buildAntdTheme,
  DEFAULT_FONT_SCALE,
  DEFAULT_MODE,
  DEFAULT_PALETTE,
  PALETTES,
  type ThemeFontScale,
  type ThemeMode,
  type ThemePaletteId,
} from './palettes';

const STORAGE_KEY = 'ai_group_theme';

interface ThemeState {
  palette: ThemePaletteId;
  mode: ThemeMode;
  fontScale: ThemeFontScale;
}

interface ThemeContextValue extends ThemeState {
  /** 'system' resolved against the OS preference. */
  resolvedMode: 'light' | 'dark';
  setPalette: (palette: ThemePaletteId) => void;
  setMode: (mode: ThemeMode) => void;
  setFontScale: (fontScale: ThemeFontScale) => void;
}

const VALID_PALETTES = new Set(PALETTES.map((p) => p.id));
const VALID_MODES = new Set<ThemeMode>(['light', 'dark', 'system']);
const VALID_FONT_SCALES = new Set<ThemeFontScale>(['sm', 'base', 'md', 'lg']);

const DEFAULT_STATE: ThemeState = {
  palette: DEFAULT_PALETTE,
  mode: DEFAULT_MODE,
  fontScale: DEFAULT_FONT_SCALE,
};

function readStoredState(): ThemeState {
  if (typeof window === 'undefined') {
    return DEFAULT_STATE;
  }
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return DEFAULT_STATE;
    }
    const parsed = JSON.parse(raw) as Partial<ThemeState>;
    return {
      palette:
        parsed.palette && VALID_PALETTES.has(parsed.palette) ? parsed.palette : DEFAULT_PALETTE,
      mode: parsed.mode && VALID_MODES.has(parsed.mode) ? parsed.mode : DEFAULT_MODE,
      fontScale:
        parsed.fontScale && VALID_FONT_SCALES.has(parsed.fontScale)
          ? parsed.fontScale
          : DEFAULT_FONT_SCALE,
    };
  } catch {
    return DEFAULT_STATE;
  }
}

function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) {
    return false;
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function applyToDocument(state: ThemeState, resolvedMode: 'light' | 'dark'): void {
  if (typeof document === 'undefined') {
    return;
  }
  const el = document.documentElement;
  // 某些测试环境仅 mock 了 document.getElementById，没有 documentElement，需容错。
  if (!el || typeof el.classList === 'undefined') {
    return;
  }
  el.classList.toggle('dark', resolvedMode === 'dark');
  el.setAttribute('data-palette', state.palette);
  el.setAttribute('data-font-scale', state.fontScale);
  if (el.style) {
    el.style.colorScheme = resolvedMode;
  }
}

/**
 * Applies the persisted theme to <html> synchronously before React renders,
 * avoiding a light-mode flash for users who chose a dark/alternate palette.
 * Call once from main.tsx before createRoot().render().
 */
export function bootstrapTheme(): void {
  const state = readStoredState();
  const resolvedMode =
    state.mode === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : state.mode;
  applyToDocument(state, resolvedMode);
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ThemeState>(() => readStoredState());
  const [systemDark, setSystemDark] = useState<boolean>(() => systemPrefersDark());

  useEffect(() => {
    if (!window.matchMedia) {
      return;
    }
    const mql = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = (event: MediaQueryListEvent) => setSystemDark(event.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);

  const resolvedMode: 'light' | 'dark' =
    state.mode === 'system' ? (systemDark ? 'dark' : 'light') : state.mode;

  useEffect(() => {
    applyToDocument(state, resolvedMode);
  }, [state, resolvedMode]);

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch {
      /* ignore quota / privacy-mode errors */
    }
  }, [state]);

  const setPalette = useCallback(
    (palette: ThemePaletteId) => setState((prev) => ({ ...prev, palette })),
    [],
  );
  const setMode = useCallback((mode: ThemeMode) => setState((prev) => ({ ...prev, mode })), []);
  const setFontScale = useCallback(
    (fontScale: ThemeFontScale) => setState((prev) => ({ ...prev, fontScale })),
    [],
  );

  const antdThemeConfig = useMemo(
    () => buildAntdTheme(state.palette, resolvedMode === 'dark', state.fontScale),
    [state.palette, state.fontScale, resolvedMode],
  );

  const value = useMemo<ThemeContextValue>(
    () => ({ ...state, resolvedMode, setPalette, setMode, setFontScale }),
    [state, resolvedMode, setPalette, setMode, setFontScale],
  );

  return (
    <ThemeContext.Provider value={value}>
      <ConfigProvider theme={antdThemeConfig}>{children}</ConfigProvider>
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error('useTheme must be used within ThemeProvider');
  }
  return ctx;
}
