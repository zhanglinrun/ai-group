import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { ConfigProvider } from 'antd';
import {
  buildAntdTheme,
  type ThemeFontScale,
  type ThemeMode,
  type ThemePaletteId,
} from './palettes';
import {
  applyToDocument,
  persistThemeState,
  readStoredState,
  systemPrefersDark,
  ThemeContext,
  type ThemeContextValue,
  type ThemeState,
} from './themeRuntime';

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
    persistThemeState(state);
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
