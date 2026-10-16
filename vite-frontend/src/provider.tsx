import type { NavigateOptions } from "react-router-dom";
import * as React from "react";

import { HeroUIProvider } from "@heroui/system";
import { useHref, useNavigate } from "react-router-dom";
import { Toaster } from 'react-hot-toast';
import { ThemeProvider } from '@/components/theme-provider';
import { I18nProvider } from "@react-aria/i18n";

declare module "@react-types/shared" {
  interface RouterConfig {
    routerOptions: NavigateOptions;
  }
}

export interface ProvidersProps {
  children: React.ReactNode;
}

export function Provider({ children }: ProvidersProps) {
  const navigate = useNavigate();
  const [locale, setLocale] = React.useState(localStorage.getItem('panel_locale') || 'zh-CN');
  React.useEffect(() => {
    const update = () => setLocale(localStorage.getItem('panel_locale') || 'zh-CN');
    window.addEventListener('panelLocaleChanged', update);
    return () => window.removeEventListener('panelLocaleChanged', update);
  }, []);
  const [workspace, setWorkspace] = React.useState(localStorage.getItem('panel_workspace') || 'admin');
  React.useEffect(() => {
    const update = () => setWorkspace(localStorage.getItem('panel_workspace') || 'admin');
    window.addEventListener('panelWorkspaceChanged', update);
    return () => window.removeEventListener('panelWorkspaceChanged', update);
  }, []);
  React.useEffect(() => { document.documentElement.lang = locale; }, [locale]);

  return (
    <I18nProvider locale={locale}>
      <HeroUIProvider navigate={navigate} useHref={useHref}>
        <ThemeProvider>
          <React.Fragment key={`${locale}:${workspace}`}>{children}</React.Fragment>
          <Toaster 
            position="top-center"
            toastOptions={{
              duration: 2000,
              className: 'dark:bg-gray-800 dark:text-white',
              style: {
                background: 'var(--toaster-bg, #ffffff)',
                color: 'var(--toaster-color, #000000)',
                border: '1px solid var(--toaster-border, #e5e7eb)',
              },
              success: {
                duration: 2000,
                style: {
                  background: '#10b981',
                  color: '#ffffff',
                },
              },
              error: {
                duration: 2000,
                style: {
                  background: '#ef4444',
                  color: '#ffffff',
                },
              },
            }}
          />
        </ThemeProvider>
      </HeroUIProvider>
    </I18nProvider>
  );
}
