import { theme as antdTheme, type ThemeConfig } from 'antd';

export type ThemePaletteId = 'warm' | 'azure' | 'graphite' | 'sepia';
export type ThemeMode = 'light' | 'dark' | 'system';
export type ThemeFontScale = 'sm' | 'base' | 'md' | 'lg';

export interface PaletteMeta {
  id: ThemePaletteId;
  label: string;
  /** Human category shown above the label, matching the DEEIX card style. */
  category: 'warm' | 'cool' | 'neutral';
  /** Signature accent, kept in sync with CSS `--brand` (light) in global.css. */
  brand: string;
  /** Signature accent for dark mode, in sync with `.dark[data-palette] --brand`. */
  brandDark: string;
}

export const PALETTES: PaletteMeta[] = [
  { id: 'warm', label: '默认', category: 'warm', brand: '#c2683c', brandDark: '#d17a4e' },
  { id: 'azure', label: 'azure', category: 'cool', brand: '#2f80ed', brandDark: '#4a94f5' },
  {
    id: 'graphite',
    label: 'graphite',
    category: 'neutral',
    brand: '#52525b',
    brandDark: '#a1a1aa',
  },
  { id: 'sepia', label: 'sepia', category: 'warm', brand: '#a06a43', brandDark: '#c08a5e' },
];

export const MODES: { id: ThemeMode; label: string }[] = [
  { id: 'light', label: '浅色' },
  { id: 'system', label: '跟随系统' },
  { id: 'dark', label: '深色' },
];

export const FONT_SCALES: { id: ThemeFontScale; label: string }[] = [
  { id: 'sm', label: '小号' },
  { id: 'base', label: '标准' },
  { id: 'md', label: '中号' },
  { id: 'lg', label: '大号' },
];

const ANTD_FONT_SIZE: Record<ThemeFontScale, number> = {
  sm: 13,
  base: 14,
  md: 15,
  lg: 16,
};

export const DEFAULT_PALETTE: ThemePaletteId = 'warm';
export const DEFAULT_MODE: ThemeMode = 'system';
export const DEFAULT_FONT_SCALE: ThemeFontScale = 'base';

export function getPalette(id: ThemePaletteId): PaletteMeta {
  return PALETTES.find((p) => p.id === id) ?? PALETTES[0];
}

/** Brand color for the current palette + resolved mode (for previews / inline styles). */
export function getBrandColor(id: ThemePaletteId, isDark: boolean): string {
  const palette = getPalette(id);
  return isDark ? palette.brandDark : palette.brand;
}

/** Build the AntD ThemeConfig so AntD controls follow the active palette/mode/font scale. */
export function buildAntdTheme(
  paletteId: ThemePaletteId,
  isDark: boolean,
  fontScale: ThemeFontScale,
): ThemeConfig {
  return {
    algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: getBrandColor(paletteId, isDark),
      borderRadius: 8,
      fontSize: ANTD_FONT_SIZE[fontScale],
    },
  };
}
