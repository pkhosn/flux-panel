import React, { useEffect } from 'react';
import { useTheme } from '@heroui/use-theme';
import { applyTheme, getPreferredTheme } from '@/utils/theme';

interface ThemeProviderProps {
  children: React.ReactNode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
  const { theme, setTheme } = useTheme();

  useEffect(() => {
    const preferredTheme = getPreferredTheme();
    if (theme !== preferredTheme) {
      setTheme(preferredTheme);
      return;
    }
    applyTheme(preferredTheme);
  }, [theme, setTheme]);

  return <>{children}</>;
};
