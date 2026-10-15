import english from '@/locales/en.json';

/** Translate application UI strings; user supplied names and content remain unchanged. */
export function translate(text: string): string {
  if (localStorage.getItem('panel_locale') !== 'en-US') return text;
  return (english as Record<string, string>)[text] || text;
}
