import { useEffect, useState } from 'react';
import axios from 'axios';
import { Button } from '@heroui/button';
import { Card, CardBody } from '@heroui/card';
import { Input, Textarea } from '@heroui/input';
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from '@heroui/modal';
import { Chip } from '@heroui/chip';
import toast from 'react-hot-toast';
import { activateProbe, deleteProbe, getProbeList, issueProbeToken } from '@/api';
import { copyText } from '@/utils/clipboard';
import { translate as t } from '@/utils/locale';

type Probe = {
  id: number; name: string; status: number; server_ip: string;
  detected_ipv4?: string; detected_ipv6?: string; version?: string;
};
type Info = { cpu_usage?: number; memory_usage?: number; bytes_received?: number; bytes_transmitted?: number; uptime?: number };

function panelURL() {
  const base = axios.defaults.baseURL || import.meta.env.VITE_API_BASE || window.location.origin;
  return new URL(base, window.location.origin).origin;
}
const quote = (value: string) => "'" + value.replace(/'/g, "'\\''") + "'";
const bytes = (value = 0) => value >= 1024 ** 3 ? `${(value / 1024 ** 3).toFixed(1)} GB` : `${(value / 1024 ** 2).toFixed(1)} MB`;

export default function ServerProbes({ onActivated }: { onActivated: () => void }) {
  const [probes, setProbes] = useState<Probe[]>([]);
  const [info, setInfo] = useState<Record<number, Info>>({});
  const [busy, setBusy] = useState(false);
  const [installOpen, setInstallOpen] = useState(false);
  const [command, setCommand] = useState('');
  const [address, setAddress] = useState(panelURL);
  const [deleting, setDeleting] = useState<Probe | null>(null);
  const [selected, setSelected] = useState<Probe | null>(null);
  const [name, setName] = useState('');
  const [ports, setPorts] = useState('10000-60000');

  async function load() {
    const result = await getProbeList();
    if (result.code === 0) setProbes(result.data || []);
    else toast.error(result.msg);
  }
  useEffect(() => {
    let disposed = false;
    let socket: WebSocket | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;
    void load();
    const connect = () => {
      if (disposed) return;
      const url = new URL('/system-info', panelURL());
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      url.searchParams.set('type', '0');
      url.searchParams.set('secret', localStorage.getItem('token') || '');
      socket = new WebSocket(url);
      socket.onmessage = event => {
        try {
          const message = JSON.parse(event.data);
          if (message.type === 'status') {
            void load();
            if (message.data === 0) setInfo(previous => { const next = { ...previous }; delete next[message.id]; return next; });
          }
          if (message.type === 'info') {
            const data = typeof message.data === 'string' ? JSON.parse(message.data) : message.data;
            setInfo(previous => ({ ...previous, [message.id]: data }));
          }
        } catch { /* Ignore malformed telemetry. */ }
      };
      socket.onclose = () => { if (!disposed) retry = setTimeout(connect, 3000); };
    };
    connect();
    const poll = setInterval(() => { void load(); }, 15000);
    return () => { disposed = true; clearInterval(poll); clearTimeout(retry); socket?.close(); };
  }, []);

  async function generate() {
    let url: URL;
    try {
      url = new URL(address);
      if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw Error();
    } catch { toast.error(t('请输入有效的面板地址')); return; }
    setBusy(true);
    try {
      const result = await issueProbeToken();
      if (result.code !== 0) { toast.error(result.msg); return; }
      setCommand(`curl -fsSL https://raw.githubusercontent.com/pkhosn/flux-panel/refs/heads/beta/install.sh -o /tmp/flux-install.sh && bash /tmp/flux-install.sh -p ${quote(url.origin)} -r ${quote(result.data.token)}`);
    } finally { setBusy(false); }
  }
  async function activate() {
    if (!selected) return;
    setBusy(true);
    try {
      const result = await activateProbe({ id: selected.id, name, ports });
      if (result.code !== 0) { toast.error(result.msg); return; }
      toast.success(t('已启用转发'));
      setSelected(null);
      onActivated();
    } finally { setBusy(false); }
  }
  async function remove() {
    if (!deleting) return;
    setBusy(true);
    try {
      const result = await deleteProbe(deleting.id);
      if (result.code !== 0) { toast.error(result.msg); return; }
      setDeleting(null);
      toast.success(t('删除成功'));
      await load();
    } finally { setBusy(false); }
  }
  async function copy(value: string) {
    if (await copyText(value)) toast.success(t('已复制'));
    else toast.error(t('复制失败，请手动复制'));
  }

  return <section>
    <div className="flex flex-wrap justify-between items-center gap-3 mb-5">
      <p className="text-sm text-default-500">{t('仅显示本人安装的探针，启用转发后进入转发节点。')}</p>
      <div className="flex gap-2">
        <Button size="sm" variant="flat" onPress={() => { void load(); }}>{t('刷新')}</Button>
        <Button size="sm" color="primary" onPress={() => { setCommand(''); setInstallOpen(true); }}>{t('安装探针')}</Button>
      </div>
    </div>
    {!probes.length && <div className="py-12 text-center text-default-500">{t('暂无服务器探针')}</div>}
    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
      {probes.map(probe => {
        const stats = probe.status === 1 ? info[probe.id] : undefined;
        return <Card key={probe.id} shadow="none" className="border border-default-200"><CardBody className="gap-3 p-4">
          <div className="flex justify-between gap-2 items-center"><h3 className="font-semibold truncate">{probe.name}</h3><Chip size="sm" color={probe.status === 1 ? 'success' : 'default'} variant="flat">{t(probe.status === 1 ? '在线' : '离线')}</Chip></div>
          {([['IPv4', probe.detected_ipv4 || probe.server_ip], ['IPv6', probe.detected_ipv6]] as const).map(([label, value]) => <div key={label} className="flex gap-3 text-sm min-w-0"><span className="text-default-500 w-10 shrink-0">{label}</span>{value ? <button className="truncate text-left hover:text-primary" title={value} onClick={() => { void copy(value); }}>{value}</button> : <span className="text-default-400">{t('未获取')}</span>}</div>)}
          <div className="grid grid-cols-2 gap-2 text-sm">
            <span>CPU <b>{stats ? `${Number(stats.cpu_usage || 0).toFixed(1)}%` : '—'}</b></span>
            <span>{t('内存')} <b>{stats ? `${Number(stats.memory_usage || 0).toFixed(1)}%` : '—'}</b></span>
            <span>{t('上传')} {stats ? bytes(stats.bytes_transmitted) : '—'}</span>
            <span>{t('下载')} {stats ? bytes(stats.bytes_received) : '—'}</span>
          </div>
          <div className="text-xs text-default-500">{t('运行时间')} {stats ? `${Math.floor((stats.uptime || 0) / 86400)}d ${Math.floor(((stats.uptime || 0) % 86400) / 3600)}h` : '—'} · {probe.version || '—'}</div>
          <div className="flex gap-2"><Button size="sm" variant="light" color="danger" onPress={() => setDeleting(probe)}>{t('删除')}</Button><Button className="flex-1" size="sm" variant="flat" color="primary" isDisabled={probe.status !== 1} onPress={() => { setSelected(probe); setName(probe.name); setPorts('10000-60000'); }}>{t('启用转发')}</Button></div>
        </CardBody></Card>;
      })}
    </div>
    <Modal isOpen={installOpen} onOpenChange={open => { if (!busy) { setInstallOpen(open); if (!open) setCommand(''); } }} placement="center" scrollBehavior="inside">
      <ModalContent><ModalHeader>{t('安装探针')}</ModalHeader><ModalBody>
        <Input label={t('面板地址')} description={t('填写 VPS 可以访问的面板地址，包含 http:// 或 https:// 及端口。')} value={address} onValueChange={value => { setAddress(value); setCommand(''); }} />
        <p className="text-sm text-default-500">{t('安装命令15分钟有效，仅可使用一次。请在新服务器以 root 执行。')}</p>
        <Button isLoading={busy} onPress={generate}>{t('生成安装命令')}</Button>
        {!!command && <><Textarea aria-label={t('安装命令')} value={command} isReadOnly minRows={5} /><Button onPress={() => { void copy(command); }}>{t('复制安装命令')}</Button></>}
      </ModalBody><ModalFooter><Button variant="flat" onPress={() => { setInstallOpen(false); setCommand(''); }}>{t('关闭')}</Button></ModalFooter></ModalContent>
    </Modal>
    <Modal isOpen={!!deleting} isDismissable={!busy} onOpenChange={open => { if (!open && !busy) setDeleting(null); }} placement="center">
      <ModalContent><ModalHeader>{t('删除探针')}</ModalHeader><ModalBody>
        <p>{deleting?.name}</p><p className="text-sm text-default-500">{t('删除后凭证立即失效。服务器上的 Agent 需自行卸载，此操作不会执行远程命令。')}</p>
      </ModalBody><ModalFooter><Button variant="flat" isDisabled={busy} onPress={() => setDeleting(null)}>{t('取消')}</Button><Button color="danger" isLoading={busy} onPress={remove}>{t('确认删除')}</Button></ModalFooter></ModalContent>
    </Modal>
    <Modal isOpen={!!selected} isDismissable={!busy} onOpenChange={open => { if (!open && !busy) setSelected(null); }} placement="center">
      <ModalContent><ModalHeader>{t('启用转发')}</ModalHeader><ModalBody>
        <Input label={t('节点名称')} value={name} onValueChange={setName} maxLength={100} />
        <Input label={t('可用端口范围')} value={ports} onValueChange={setPorts} placeholder="10000-60000" />
        <p className="text-sm text-default-500">{t('确认后启用现有 Agent 的转发能力，无需重新安装。')}</p>
      </ModalBody><ModalFooter><Button variant="flat" isDisabled={busy} onPress={() => setSelected(null)}>{t('取消')}</Button><Button color="primary" isLoading={busy} onPress={activate}>{t('确认启用')}</Button></ModalFooter></ModalContent>
    </Modal>
  </section>;
}
