import { createContext, useContext } from 'react';

import {
  DEFAULT_FONT_SCALE,
  DEFAULT_MODE,
  DEFAULT_PALETTE,
  PALETTES,
  type ThemeFontScale,
  type ThemeMode,
  type ThemePaletteId,
} from './palettes';

const STORAGE_KEY = 'ai_group_theme';

export interface ThemeState {
  palette: ThemePaletteId;
  mode: ThemeMode;
  fontScale: ThemeFontScale;
}

export interface ThemeContextValue extends ThemeState {
  /** 'system' resolved against the OS preference. */
  resolvedMode: 'light' | 'dark';
  setPalette: (palette: ThemePaletteId) => void;
  setMode: (mode: ThemeMode) => void;
  setFontScale: (fontScale: ThemeFontScale) => void;
}

const VALID_PALETTES = new Set(PALETTES.map((palette) => palette.id));
const VALID_MODES = new Set<ThemeMode>(['light', 'dark', 'system']);
const VALID_FONT_SCALES = new Set<ThemeFontScale>(['sm', 'base', 'md', 'lg']);

const DEFAULT_STATE: ThemeState = {
  palette: DEFAULT_PALETTE,
  mode: DEFAULT_MODE,
  fontScale: DEFAULT_FONT_SCALE,
};

export function readStoredState(): ThemeState {
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

export function persistThemeState(state: ThemeState): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    /* Ignore quota and privacy-mode errors. */
  }
}

export function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || !window.matchMedia) {
    return false;
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

export function applyToDocument(state: ThemeState, resolvedMode: 'light' | 'dark'): void {
  if (typeof document === 'undefined') {
    return;
  }
  const element = document.documentElement;
  // 某些测试环境仅 mock 了 document.getElementById，没有 documentElement，需容错。
  if (!element || typeof element.classList === 'undefined') {
    return;
  }
  element.classList.toggle('dark', resolvedMode === 'dark');
  element.setAttribute('data-palette', state.palette);
  element.setAttribute('data-font-scale', state.fontScale);
  if (element.style) {
    element.style.colorScheme = resolvedMode;
  }
}

/** Apply the persisted theme before React renders to avoid a theme flash. */
export function bootstrapTheme(): void {
  const state = readStoredState();
  const resolvedMode =
    state.mode === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : state.mode;
  applyToDocument(state, resolvedMode);
}

export const ThemeContext = createContext<ThemeContextValue | null>(null);

export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useTheme must be used within ThemeProvider');
  }
  return context;
}
