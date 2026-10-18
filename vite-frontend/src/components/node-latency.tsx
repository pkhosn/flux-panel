import { useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { getNodeLatency } from '@/api';
import { translate } from '@/utils/locale';

type Result = { name: string; status: string; avg?: number; loss?: number; city?: string };
type Measurement = { status: string; target?: string; testedAt?: number; results?: Result[]; message?: string };

export function NodeLatency({ id }: { id: number }) {
  const [data, setData] = useState<Measurement>({ status: 'idle' });
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setTimeout>;
    const read = async (run: boolean) => {
      try {
        const response = await getNodeLatency(id, run);
        if (disposed) return;
        const next: Measurement = response.code === 0 ? response.data : { status: 'error', message: response.msg };
        setData(next);
        setBusy(next.status === 'pending');
        if (next.status === 'pending') timer = setTimeout(() => read(false), 3000);
      } catch {
        if (!disposed) { setBusy(false); setData({ status: 'error', message: translate('探测失败，请稍后重试') }); }
      }
    };
    read(refresh > 0);
    return () => { disposed = true; clearTimeout(timer); };
  }, [id, refresh]);
  return <div className="flex items-center justify-between gap-2 text-xs border-t border-divider pt-2" aria-label={translate('三网延迟')}>
    <div className="flex flex-wrap gap-x-3 gap-y-1 min-w-0" title={data.message ? translate(data.message) : data.target}>
      {['电信', '联通', '移动'].map(name => {
        const row = data.results?.find(item => item.name === name);
        const value = row?.status === 'ok' && row.avg != null ? `${Math.round(row.avg)}ms` : row?.status === 'timeout' ? translate('超时') : row?.status === 'unavailable' ? '—' : busy ? '…' : '—';
        const title = row ? `${row.city || translate('暂无探测点')} · ${translate('丢包率')} ${row.loss ?? '—'}% · ${data.testedAt ? new Date(data.testedAt).toLocaleString() : ''} · ${data.target}` : translate(data.message || '国内探测点到节点，手动测试，结果缓存15分钟');
        return <span key={name} title={title}>{translate(name)} <span className={row?.status === 'timeout' ? 'text-warning' : 'font-mono'}>{value}</span></span>;
      })}
    </div>
    <Button size="sm" variant="light" className="h-6 min-w-10 px-1 text-xs shrink-0" isDisabled={busy} onPress={() => { setBusy(true); setRefresh(value => value + 1); }}>{translate(busy ? '测试中' : data.status === 'error' ? '重试' : '测试')}</Button>
  </div>;
}
