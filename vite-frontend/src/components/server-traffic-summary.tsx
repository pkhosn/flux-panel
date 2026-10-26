import { translate as t } from '@/utils/locale';

export type ServerTraffic = {
  online: boolean;
  uploadSpeed?: number;
  downloadSpeed?: number;
  uploadTraffic?: number;
  downloadTraffic?: number;
};

export function ServerTrafficSummary({ servers, scope }: { servers: ServerTraffic[]; scope: string }) {
  const measured = servers.filter(server => server.online && server.uploadTraffic !== undefined);
  const sum = (field: keyof Omit<ServerTraffic, 'online'>) => measured.reduce((total, server) => {
    const value = server[field];
    return total + (value !== undefined && Number.isFinite(value) && value >= 0 ? value : 0);
  }, 0);
  const format = (value: number, speed = false) => {
    const index = value > 0 ? Math.min(4, Math.floor(Math.log(value) / Math.log(1024))) : 0;
    return `${Number((value / 1024 ** index).toFixed(2))} ${['B', 'KB', 'MB', 'GB', 'TB'][index]}${speed ? '/s' : ''}`;
  };
  const title = `${scope} · ${t('在线')} ${servers.filter(server => server.online).length}/${servers.length} · ${t('已收到数据')} ${measured.length}\n${t('在线服务器自启动以来累计流量')}\n${t('上传')} ${measured.length ? format(sum('uploadTraffic')) : '—'} · ${t('下载')} ${measured.length ? format(sum('downloadTraffic')) : '—'}`;
  return <div aria-label={t('服务器总速率')} title={title} className="flex items-center justify-end gap-4 text-xs sm:text-sm tabular-nums whitespace-nowrap">
    <span className="inline-flex items-center gap-1.5 text-primary-600 dark:text-primary-400" aria-label={t('总上传')}>
      <svg aria-hidden="true" className="w-4 h-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path strokeLinecap="round" strokeLinejoin="round" d="M12 20V4m-6 6 6-6 6 6" /></svg>
      <span className="font-medium">{measured.length ? format(sum('uploadSpeed'), true) : '—'}</span>
    </span>
    <span className="inline-flex items-center gap-1.5 text-success-600 dark:text-success-400" aria-label={t('总下载')}>
      <svg aria-hidden="true" className="w-4 h-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m-6-6 6 6 6-6" /></svg>
      <span className="font-medium">{measured.length ? format(sum('downloadSpeed'), true) : '—'}</span>
    </span>
  </div>;
}
