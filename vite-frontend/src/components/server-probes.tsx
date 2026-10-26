import { useEffect, useState } from 'react';
import axios from 'axios';
import { Button } from '@heroui/button';
import { ServerCard, serverCardGrid } from '@/components/server-card';
import { Input, Textarea } from '@heroui/input';
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from '@heroui/modal';
import toast from 'react-hot-toast';
import { activateProbe, deleteProbe, getProbeList, issueProbeToken } from '@/api';
import { copyText } from '@/utils/clipboard';
import { translate as t } from '@/utils/locale';

type Probe = {
  id: number; name: string; status: number; server_ip: string;
  detected_ipv4?: string; detected_ipv6?: string; version?: string;
};
type Info = { cpu_usage?: number; memory_usage?: number; bytes_received?: number; bytes_transmitted?: number; uptime?: number; uploadSpeed?: number; downloadSpeed?: number };

function panelURL() {
  const base = axios.defaults.baseURL || import.meta.env.VITE_API_BASE || window.location.origin;
  return new URL(base, window.location.origin).origin;
}
const quote = (value: string) => "'" + value.replace(/'/g, "'\\''") + "'";

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
            setInfo(previous => {
              const last = previous[message.id];
              const elapsed = Number(data.uptime) - Number(last?.uptime);
              const speed = (field: 'bytes_transmitted' | 'bytes_received') => last && elapsed > 0 && elapsed <= 10
                ? Math.max(0, (Number(data[field] || 0) - Number(last[field] || 0)) / elapsed) : 0;
              return { ...previous, [message.id]: { ...data, uploadSpeed: speed('bytes_transmitted'), downloadSpeed: speed('bytes_received') } };
            });
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
    <div className={serverCardGrid}>
      {probes.map(probe => {
        const stats = probe.status === 1 ? info[probe.id] : undefined;
        return <ServerCard key={probe.id} node={{
          id: probe.id, name: probe.name, ip: probe.server_ip, serverIp: probe.server_ip,
          detectedIpv4: probe.detected_ipv4, detectedIpv6: probe.detected_ipv6,
          version: probe.version, port: '', status: probe.status,
          connectionStatus: probe.status === 1 ? 'online' : 'offline',
          systemInfo: stats ? {
            cpuUsage: Number(stats.cpu_usage || 0), memoryUsage: Number(stats.memory_usage || 0),
            uploadTraffic: Number(stats.bytes_transmitted || 0), downloadTraffic: Number(stats.bytes_received || 0),
            uploadSpeed: stats.uploadSpeed || 0, downloadSpeed: stats.downloadSpeed || 0, uptime: Number(stats.uptime || 0),
          } : null,
        }} actions={<div className="flex gap-1.5">
          <Button className="flex-1 min-h-8" size="sm" variant="flat" color="primary" isDisabled={probe.status !== 1} onPress={() => { setSelected(probe); setName(probe.name); setPorts('10000-60000'); }}>{t('启用转发')}</Button>
          <Button className="flex-1 min-h-8" size="sm" variant="flat" color="danger" onPress={() => setDeleting(probe)}>{t('删除')}</Button>
        </div>} />;
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
