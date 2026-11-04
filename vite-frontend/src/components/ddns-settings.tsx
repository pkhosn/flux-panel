import { useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Input } from '@heroui/input';
import { Switch } from '@heroui/switch';
import { Select, SelectItem } from '@/components/select';
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from '@heroui/modal';
import toast from 'react-hot-toast';
import Network from '@/api/network';

interface Task {
  id: number; name: string; source: string; record_id: string; record_name: string;
  enabled: number; source_ip: string | null; target_ip: string | null;
  checked_at: number | null; updated_at: number | null; error: string; running: boolean;
}
interface RecordItem { id: string; name: string; content: string; proxied: boolean }
const empty = { name: '', source: '', recordId: '', enabled: true };
const time = (value: number | null) => value ? new Date(value).toLocaleString() : '尚未同步';

export default function DdnsSettings() {
  const [zoneId, setZoneId] = useState('');
  const [token, setToken] = useState('');
  const [configured, setConfigured] = useState(false);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [records, setRecords] = useState<RecordItem[]>([]);
  const [editing, setEditing] = useState<(typeof empty & { id?: number }) | null>(null);
  const [busy, setBusy] = useState(false);
  const [remove, setRemove] = useState<Task | null>(null);
  const [loadError, setLoadError] = useState('');
  const call = async (path: string, data = {}) => {
    const response = await Network.post(`/ddns/${path}`, data);
    if (response.code !== 0) throw new Error(response.msg || '操作失败');
    return response.data;
  };
  const refresh = async (initial = false) => {
    try {
      const data = await call('list');
      setConfigured(data.configured); setTasks(data.tasks); setLoadError('');
      if (initial) setZoneId(data.zoneId);
    } catch (error) { setLoadError((error as Error).message); }
  };
  useEffect(() => {
    void refresh(true);
    const timer = setInterval(() => { void refresh(); }, 5000);
    return () => clearInterval(timer);
  }, []);
  const act = async (fn: () => Promise<void>) => {
    setBusy(true);
    try { await fn(); } catch (error) { toast.error((error as Error).message); }
    finally { setBusy(false); }
  };
  const open = (task?: Task) => act(async () => {
    const items = await call('records'); setRecords(items);
    setEditing(task ? { id: task.id, name: task.name, source: task.source, recordId: task.record_id, enabled: task.enabled === 1 } : { ...empty });
  });
  return <div className="space-y-3">
    <Card shadow="none" className="border border-divider rounded-xl">
      <CardHeader className="px-4 py-3 border-b border-divider font-semibold">Cloudflare 凭证</CardHeader>
      <CardBody className="gap-4 p-4">
        <p className="text-sm text-default-500">将 DDNS 域名的 IPv4 同步到指定 A 记录，同名其他记录保持不变。每分钟检查一次，DNS 缓存可能延迟生效。</p>
        <div className="grid gap-4 md:grid-cols-2">
          <Input label="Zone ID" value={zoneId} onValueChange={setZoneId} maxLength={32} />
          <Input label="API Token" type="password" autoComplete="new-password" value={token} onValueChange={setToken}
            placeholder={configured ? '已配置，留空保留原 Token' : '请输入 API Token'} />
        </div>
        <p className="text-xs text-default-500">Token 仅授予此 Zone 的 Zone Read、DNS Read / Edit 权限。凭证加密保存；备份恢复时须保留原 JWT_SECRET，否则需重新填写 Token。保存会验证读取权限，编辑权限在首次同步时验证。</p>
        <Button color="primary" size="sm" className="self-start" isLoading={busy} onPress={() => void act(async () => {
          await call('account', { zoneId, token }); setToken(''); await refresh(true); toast.success('Cloudflare 凭证已保存');
        })}>验证并保存凭证</Button>
      </CardBody>
    </Card>
    <div className="flex flex-wrap items-center justify-between gap-2 pt-2">
      <h2 className="font-semibold">同步任务</h2>
      <Button size="sm" color="primary" isDisabled={!configured || busy} onPress={() => void open()}>新增同步</Button>
    </div>
    {loadError && <p role="alert" className="text-danger text-sm">{loadError}</p>}
    {!tasks.length && <p className="text-sm text-default-500 py-6 text-center">暂无任务。先保存凭证，再选择需要自动更新的记录。</p>}
    {tasks.map(task => <Card key={task.id} shadow="none" className="border border-divider rounded-xl">
      <CardBody className="p-4 gap-3">
        <div className="flex items-center justify-between gap-3">
          <h3 id={`ddns-task-${task.id}`} className="font-medium break-all">{task.name}</h3>
          <Switch size="sm" aria-labelledby={`ddns-task-${task.id}`} isSelected={task.enabled === 1} isDisabled={busy || task.running}
            onValueChange={enabled => void act(async () => { await call('enable', { id: task.id, enabled }); await refresh(); })} />
        </div>
        <div className="grid gap-3 sm:grid-cols-2 text-sm min-w-0">
          <div className="break-all"><p className="text-default-500 text-xs mb-1">来源 DDNS</p>{task.source}<p className="text-default-500 mt-1">{task.source_ip || '尚未获取'}</p></div>
          <div className="break-all"><p className="text-default-500 text-xs mb-1">目标 A 记录</p>{task.record_name}<p className="text-default-500 mt-1">{task.target_ip || '尚未获取'}</p><p className="text-default-400 text-xs mt-1">ID：{task.record_id}</p></div>
        </div>
        <p className="text-xs text-default-500">检查：{time(task.checked_at)} · 更新：{time(task.updated_at)}</p>
        <p role="status" className={`text-sm ${task.error ? 'text-danger' : task.running ? 'text-primary' : 'text-success'}`}>
          {task.running ? '正在同步…' : task.enabled !== 1 ? '已暂停' : task.error || (task.checked_at ? '同步正常' : '等待首次检查')}
        </p>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" variant="flat" isDisabled={busy || task.enabled !== 1} isLoading={task.running}
            onPress={() => void act(async () => { await call('sync', { id: task.id }); await refresh(); toast.success('已提交立即同步，结果会自动更新'); })}>立即同步</Button>
          <Button size="sm" variant="flat" isDisabled={busy || task.running} onPress={() => void open(task)}>编辑</Button>
          <Button size="sm" variant="light" color="danger" isDisabled={busy || task.running} onPress={() => setRemove(task)}>删除</Button>
        </div>
      </CardBody>
    </Card>)}
    <Modal isOpen={editing !== null} onClose={() => { if (!busy) setEditing(null); }} isDismissable={!busy}>
      <ModalContent><ModalHeader>{editing?.id ? '编辑同步任务' : '新增同步任务'}</ModalHeader><ModalBody>
        <Input label="名称" value={editing?.name || ''} maxLength={64} onValueChange={name => setEditing(e => e && { ...e, name })} />
        <Input label="来源 DDNS 域名" placeholder="a.hinet.com" value={editing?.source || ''} onValueChange={source => setEditing(e => e && { ...e, source })} />
        <Select label="目标 A 记录" selectedKeys={editing?.recordId ? [editing.recordId] : []}
          onSelectionChange={keys => setEditing(e => e && { ...e, recordId: String(Array.from(keys)[0] || '') })}>
          {records.map(record => <SelectItem key={record.id} isDisabled={record.proxied}
            textValue={`${record.name} → ${record.content}`}>
            {record.name} → {record.content}{record.proxied ? '（请先关闭代理）' : ''}
          </SelectItem>)}
        </Select>
        <p className="text-xs text-default-500 break-all">绑定记录 ID：{editing?.recordId || '未选择'}。请核实当前 IP 属于对应服务器；同名不同 IP 是不同记录。来源必须只解析到一个公网 IPv4。</p>
        <Switch aria-label="启用同步" isSelected={editing?.enabled ?? true} onValueChange={enabled => setEditing(e => e && { ...e, enabled })}>启用同步</Switch>
      </ModalBody><ModalFooter>
        <Button variant="flat" isDisabled={busy} onPress={() => setEditing(null)}>取消</Button>
        <Button color="primary" isLoading={busy} isDisabled={!editing?.recordId || !editing.name.trim() || !editing.source.trim()}
          onPress={() => void act(async () => { await call('save', editing!); setEditing(null); await refresh(); toast.success('同步任务已保存'); })}>保存任务</Button>
      </ModalFooter></ModalContent>
    </Modal>
    <Modal isOpen={remove !== null} onClose={() => { if (!busy) setRemove(null); }}>
      <ModalContent><ModalHeader>删除同步任务</ModalHeader><ModalBody>确定删除“{remove?.name}”？只停止同步，Cloudflare DNS 记录会保留。</ModalBody><ModalFooter>
        <Button variant="flat" isDisabled={busy} onPress={() => setRemove(null)}>取消</Button>
        <Button color="danger" isLoading={busy} onPress={() => void act(async () => { await call('delete', { id: remove!.id }); setRemove(null); await refresh(); toast.success('同步任务已删除'); })}>删除</Button>
      </ModalFooter></ModalContent>
    </Modal>
  </div>;
}
