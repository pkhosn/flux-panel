export type ThemeMode = "light" | "dark";

const THEME_STORAGE_KEY = "theme-mode";

export const getSystemTheme = (): ThemeMode => {
  if (typeof window === "undefined") return "light";
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
};

export const getStoredTheme = (): ThemeMode | null => {
  if (typeof window === "undefined") return null;
  const value = window.localStorage.getItem(THEME_STORAGE_KEY);
  return value === "dark" || value === "light" ? value : null;
};

export const getPreferredTheme = (): ThemeMode => {
  return getStoredTheme() || getSystemTheme();
};

export const applyTheme = (theme: ThemeMode) => {
  if (typeof document === "undefined") return;
  if (theme === "dark") {
    document.documentElement.classList.add("dark");
    document.documentElement.style.colorScheme = "dark";
  } else {
    document.documentElement.classList.remove("dark");
    document.documentElement.style.colorScheme = "light";
  }
};

export const saveTheme = (theme: ThemeMode) => {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(THEME_STORAGE_KEY, theme);
};

export const toggleTheme = (): ThemeMode => {
  const nextTheme: ThemeMode = document.documentElement.classList.contains("dark") ? "light" : "dark";
  saveTheme(nextTheme);
  applyTheme(nextTheme);
  return nextTheme;
};

