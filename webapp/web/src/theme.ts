export type ThemeMode = "light" | "dark" | "system";
export type AccentKey = "teal" | "blue" | "purple" | "green" | "orange" | "rose";

export const THEME_MODES: Array<{ key: ThemeMode; label: string }> = [
  { key: "system", label: "System" },
  { key: "light", label: "Light" },
  { key: "dark", label: "Dark" },
];

export const ACCENTS: Array<{ key: AccentKey; label: string; swatch: string }> = [
  { key: "teal", label: "Cuppa Teal", swatch: "#1b5e4b" },
  { key: "blue", label: "Blue", swatch: "#0a66c2" },
  { key: "purple", label: "Purple", swatch: "#6d3fc0" },
  { key: "green", label: "Green", swatch: "#1f8a3b" },
  { key: "orange", label: "Orange", swatch: "#b25000" },
  { key: "rose", label: "Rose", swatch: "#b33d6d" },
];

export interface ThemePrefs {
  mode: ThemeMode;
  accent: AccentKey;
}

const STORAGE_KEY = "cuppa.theme";
const DEFAULT_THEME: ThemePrefs = { mode: "system", accent: "teal" };

export function loadThemePrefs(): ThemePrefs {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULT_THEME;
    const parsed = JSON.parse(raw) as Partial<ThemePrefs>;
    return {
      mode: THEME_MODES.some((m) => m.key === parsed.mode) ? (parsed.mode as ThemeMode) : DEFAULT_THEME.mode,
      accent: ACCENTS.some((a) => a.key === parsed.accent) ? (parsed.accent as AccentKey) : DEFAULT_THEME.accent,
    };
  } catch {
    return DEFAULT_THEME;
  }
}

export function saveThemePrefs(prefs: ThemePrefs): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // Storage may be unavailable (private mode); the theme just won't persist.
  }
}

export function systemPrefersDark(): boolean {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-color-scheme: dark)").matches === true;
}

export function resolveTheme(mode: ThemeMode): "light" | "dark" {
  if (mode === "system") return systemPrefersDark() ? "dark" : "light";
  return mode;
}

/** Applies the resolved theme and accent to the document root. */
export function applyTheme(prefs: ThemePrefs): void {
  const root = document.documentElement;
  root.dataset.theme = resolveTheme(prefs.mode);
  root.dataset.accent = prefs.accent;
}
