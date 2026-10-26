import { ServerCard, serverCardGrid, type ServerCardNode } from '@/components/server-card';
import ServerProbes from '@/components/server-probes';
import { NodeLatency } from '@/components/node-latency';
import { Select, SelectItem } from '@/components/select';
import { translate } from '@/utils/locale';
import { useState, useEffect, useRef } from "react";
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Textarea } from "@heroui/input";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Switch } from "@heroui/switch";
import { Spinner } from "@heroui/spinner";
import { Alert } from "@heroui/alert";
import { Accordion, AccordionItem } from "@heroui/accordion";
import toast from 'react-hot-toast';
import axios from 'axios';


import {
  getNodeGroups, createNodeGroup, deleteNodeGroup, assignNodeGroup,
  createNode,
  getNodeList,
  updateNode,
  deleteNode,
  getNodeInstallCommand
} from "@/api";
import { copyText } from "@/utils/clipboard";

type Node = ServerCardNode;

interface NodeForm {
  id: number | null;
  name: string;
  groupName?: string;
  serverIp: string;
  port: string;
  tcpListenAddr: string;
  udpListenAddr: string;
  interfaceName: string;
  http: number; // 0 关 1 开
  tls: number;  // 0 关 1 开
  socks: number; // 0 关 1 开
}

export default function NodePage() {
  const [tab, setTab] = useState("nodes");
  const [nodeList, setNodeList] = useState<Node[]>([]);
  const [groupFilter, setGroupFilter] = useState("all");
  const [nodeGroups, setNodeGroups] = useState<{name: string; nodeCount: number}[]>([]);
  const [groupModalOpen, setGroupModalOpen] = useState(false);
  const [newGroupName, setNewGroupName] = useState('');
  const [selectedNodes, setSelectedNodes] = useState<number[]>([]);
  const [batchGroup, setBatchGroup] = useState('');
  const [groupBusy, setGroupBusy] = useState(false);
  const loadGroups = async () => {
    const result = await getNodeGroups();
    if (result.code === 0) setNodeGroups(result.data || []);
  };
  const changeGroup = async (ids: number[], name: string) => {
    if (groupBusy) return;
    setGroupBusy(true);
    try {
      const result = await assignNodeGroup(ids, name);
      if (result.code !== 0) return toast.error(result.msg || translate('操作失败'));
      setNodeList(previous => previous.map(node => ids.includes(node.id) ? { ...node, groupName: name } : node));
      setSelectedNodes([]);
      toast.success(translate('分组已更新'));
      await loadGroups();
    } finally { setGroupBusy(false); }
  };
  const visibleNodes = groupFilter === "all" ? nodeList : nodeList.filter(node => (node.groupName || "") === groupFilter.slice(6));
  const [loading, setLoading] = useState(false);
  const [dialogVisible, setDialogVisible] = useState(false);
  const [dialogTitle, setDialogTitle] = useState('');
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [nodeToDelete, setNodeToDelete] = useState<Node | null>(null);
  const [protocolDisabled, setProtocolDisabled] = useState(false);
  const [protocolDisabledReason, setProtocolDisabledReason] = useState('');
  const [form, setForm] = useState<NodeForm>({
    id: null,
    name: '',
    groupName: '',
    serverIp: '',
    port: '1000-65535',
    tcpListenAddr: '[::]',
    udpListenAddr: '[::]',
    interfaceName: '',
    http: 0,
    tls: 0,
    socks: 0
  });
  const [errors, setErrors] = useState<Record<string, string>>({});

  // 安装命令相关状态
  const [installCommandModal, setInstallCommandModal] = useState(false);
  const [installCommand, setInstallCommand] = useState('');
  const [currentNodeName, setCurrentNodeName] = useState('');

  const websocketRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<NodeJS.Timeout | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const maxReconnectAttempts = 5;

  useEffect(() => {
    loadNodes();
    initWebSocket();

    return () => {
      closeWebSocket();
    };
  }, []);

  // 加载节点列表
  const loadNodes = async () => {
    setLoading(true);
    try {
      await loadGroups();
      const res = await getNodeList();
      if (res.code === 0) {
        setNodeList(res.data.map((node: any) => ({
          ...node,
          connectionStatus: node.status === 1 ? 'online' : 'offline',
          systemInfo: null,
          copyLoading: false
        })));
      } else {
        toast.error(res.msg || '加载节点列表失败');
      }
    } catch (error) {
      toast.error(translate("网络错误，请重试"));
    } finally {
      setLoading(false);
    }
  };

  // 初始化WebSocket连接
  const initWebSocket = () => {
    if (websocketRef.current &&
        (websocketRef.current.readyState === WebSocket.OPEN ||
         websocketRef.current.readyState === WebSocket.CONNECTING)) {
      return;
    }

    if (websocketRef.current) {
      closeWebSocket();
    }

    // 构建WebSocket URL，使用axios的baseURL
    const baseUrl = axios.defaults.baseURL || (import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/');
    const wsUrl = baseUrl.replace(/^http/, 'ws').replace(/\/api\/v1\/$/, '') + `/system-info?type=0&secret=${localStorage.getItem('token')}`;

    try {
      websocketRef.current = new WebSocket(wsUrl);

      websocketRef.current.onopen = () => {
        reconnectAttemptsRef.current = 0;
      };

      websocketRef.current.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          handleWebSocketMessage(data);
        } catch (error) {
          // 解析失败时不输出错误信息
        }
      };

      websocketRef.current.onerror = () => {
        // WebSocket错误时不输出错误信息
      };

      websocketRef.current.onclose = () => {
        websocketRef.current = null;
        attemptReconnect();
      };
    } catch (error) {
      attemptReconnect();
    }
  };

  // 处理WebSocket消息
  const handleWebSocketMessage = (data: any) => {
    const { id, type, data: messageData } = data;

    if (type === 'status') {
      if (messageData === 1) {
        loadNodes();
      }
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          return {
            ...node,
            connectionStatus: messageData === 1 ? 'online' : 'offline',
            systemInfo: messageData === 0 ? null : node.systemInfo
          };
        }
        return node;
      }));
    } else if (type === 'info') {
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          try {
            let systemInfo;
            if (typeof messageData === 'string') {
              systemInfo = JSON.parse(messageData);
            } else {
              systemInfo = messageData;
            }

            const currentUpload = parseInt(systemInfo.bytes_transmitted) || 0;
            const currentDownload = parseInt(systemInfo.bytes_received) || 0;
            const currentUptime = parseInt(systemInfo.uptime) || 0;

            let uploadSpeed = 0;
            let downloadSpeed = 0;

            if (node.systemInfo && node.systemInfo.uptime) {
              const timeDiff = currentUptime - node.systemInfo.uptime;

              if (timeDiff > 0 && timeDiff <= 10) {
                const lastUpload = node.systemInfo.uploadTraffic || 0;
                const lastDownload = node.systemInfo.downloadTraffic || 0;

                const uploadDiff = currentUpload - lastUpload;
                const downloadDiff = currentDownload - lastDownload;

                const uploadReset = currentUpload < lastUpload;
                const downloadReset = currentDownload < lastDownload;

                if (!uploadReset && uploadDiff >= 0) {
                  uploadSpeed = uploadDiff / timeDiff;
                }

                if (!downloadReset && downloadDiff >= 0) {
                  downloadSpeed = downloadDiff / timeDiff;
                }
              }
            }

            return {
              ...node,
              connectionStatus: 'online',
              systemInfo: {
                cpuUsage: parseFloat(systemInfo.cpu_usage) || 0,
                memoryUsage: parseFloat(systemInfo.memory_usage) || 0,
                uploadTraffic: currentUpload,
                downloadTraffic: currentDownload,
                uploadSpeed: uploadSpeed,
                downloadSpeed: downloadSpeed,
                uptime: currentUptime
              }
            };
          } catch (error) {
            return node;
          }
        }
        return node;
      }));
    }
  };

  // 尝试重新连接
  const attemptReconnect = () => {
    if (reconnectAttemptsRef.current < maxReconnectAttempts) {
      reconnectAttemptsRef.current++;

      reconnectTimerRef.current = setTimeout(() => {
        initWebSocket();
      }, 3000 * reconnectAttemptsRef.current);
    }
  };

  // 关闭WebSocket连接
  const closeWebSocket = () => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }

    reconnectAttemptsRef.current = 0;

    if (websocketRef.current) {
      websocketRef.current.onopen = null;
      websocketRef.current.onmessage = null;
      websocketRef.current.onerror = null;
      websocketRef.current.onclose = null;

      if (websocketRef.current.readyState === WebSocket.OPEN ||
          websocketRef.current.readyState === WebSocket.CONNECTING) {
        websocketRef.current.close();
      }

      websocketRef.current = null;
    }

    setNodeList(prev => prev.map(node => ({
      ...node,
      connectionStatus: 'offline',
      systemInfo: null
    })));
  };



  // 验证IP地址格式
  const validateIp = (ip: string): boolean => {
    if (!ip || !ip.trim()) return false;

    const trimmedIp = ip.trim();

    // IPv4格式验证
    const ipv4Regex = /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/;

    // IPv6格式验证
    const ipv6Regex = /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$/;

    if (ipv4Regex.test(trimmedIp) || ipv6Regex.test(trimmedIp) || trimmedIp === 'localhost') {
      return true;
    }

    // 验证域名格式
    if (/^\d+$/.test(trimmedIp)) return false;

    const domainRegex = /^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)+$/;
    const singleLabelDomain = /^[a-zA-Z][a-zA-Z0-9\-]{0,62}$/;

    return domainRegex.test(trimmedIp) || singleLabelDomain.test(trimmedIp);
  };

  // 验证端口格式：支持 80,443,100-600
  const validatePort = (portStr: string): { valid: boolean; error?: string } => {
    if (!portStr || !portStr.trim()) {
      return { valid: false, error: '请输入端口' };
    }

    const trimmed = portStr.trim();
    const parts = trimmed.split(',').map(p => p.trim()).filter(p => p);

    if (parts.length === 0) {
      return { valid: false, error: '请输入有效的端口' };
    }

    for (const part of parts) {
      // 检查是否是端口范围 (如 100-600)
      if (part.includes('-')) {
        const range = part.split('-').map(p => p.trim());
        if (range.length !== 2) {
          return { valid: false, error: `端口范围格式错误: ${part}` };
        }

        const start = parseInt(range[0]);
        const end = parseInt(range[1]);

        if (isNaN(start) || isNaN(end)) {
          return { valid: false, error: `端口必须是数字: ${part}` };
        }

        if (start < 1 || start > 65535 || end < 1 || end > 65535) {
          return { valid: false, error: `端口范围必须在 1-65535 之间: ${part}` };
        }

        if (start >= end) {
          return { valid: false, error: `起始端口必须小于结束端口: ${part}` };
        }
      } else {
        // 单个端口
        const port = parseInt(part);
        if (isNaN(port)) {
          return { valid: false, error: `端口必须是数字: ${part}` };
        }

        if (port < 1 || port > 65535) {
          return { valid: false, error: `端口必须在 1-65535 之间: ${part}` };
        }
      }
    }

    return { valid: true };
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!form.name.trim()) {
      newErrors.name = '请输入节点名称';
    } else if (form.name.trim().length < 2) {
      newErrors.name = '节点名称长度至少2位';
    } else if (form.name.trim().length > 50) {
      newErrors.name = '节点名称长度不能超过50位';
    }

    if (form.serverIp.trim() && !validateIp(form.serverIp.trim())) {
      newErrors.serverIp = '请输入有效的IPv4、IPv6地址或域名';
    }

    const portValidation = validatePort(form.port);
    if (!portValidation.valid) {
      newErrors.port = portValidation.error || '端口格式错误';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 新增节点
  const handleAdd = () => {
    setDialogTitle('新增节点');
    setIsEdit(false);
    setDialogVisible(true);
    resetForm();
    setProtocolDisabled(true);
    setProtocolDisabledReason('节点未在线，等待节点上线后再设置');
  };

  // 编辑节点
  const handleEdit = (node: Node) => {
    setDialogTitle('编辑节点');
    setIsEdit(true);
    setForm({
      id: node.id,
      name: node.name,
      groupName: node.groupName || '',
      serverIp: node.serverIp || '',
      port: node.port || '1000-65535',
      tcpListenAddr: node.tcpListenAddr || '[::]',
      udpListenAddr: node.udpListenAddr || '[::]',
      interfaceName: (node as any).interfaceName || '',
      http: typeof node.http === 'number' ? node.http : 1,
      tls: typeof node.tls === 'number' ? node.tls : 1,
      socks: typeof node.socks === 'number' ? node.socks : 1
    });
    const offline = node.connectionStatus !== 'online';
    setProtocolDisabled(offline);
    setProtocolDisabledReason(offline ? translate("节点未在线，等待节点上线后再设置") : '');
    setDialogVisible(true);
  };

  // 删除节点
  const handleDelete = (node: Node) => {
    setNodeToDelete(node);
    setDeleteModalOpen(true);
  };

  const confirmDelete = async () => {
    if (!nodeToDelete) return;

    setDeleteLoading(true);
    try {
      const res = await deleteNode(nodeToDelete.id);
      if (res.code === 0) {
        toast.success(translate("删除成功"));
        setNodeList(prev => prev.filter(n => n.id !== nodeToDelete.id));
        setDeleteModalOpen(false);
        setNodeToDelete(null);
      } else {
        toast.error(res.msg || '删除失败');
      }
    } catch (error) {
      toast.error(translate("网络错误，请重试"));
    } finally {
      setDeleteLoading(false);
    }
  };

  // 复制安装命令
  const handleCopyInstallCommand = async (node: Node) => {
    setNodeList(prev => prev.map(n =>
      n.id === node.id ? { ...n, copyLoading: true } : n
    ));

    try {
      const res = await getNodeInstallCommand(node.id);
      if (res.code === 0 && res.data) {
        try {
          const copied = await copyText(res.data);
          if (copied) {
            toast.success(translate("安装命令已复制到剪贴板"));
          } else {
            // 复制失败，显示安装命令模态框
            setInstallCommand(res.data);
            setCurrentNodeName(node.name);
            setInstallCommandModal(true);
          }
        } catch (copyError) {
          // 复制失败，显示安装命令模态框
          setInstallCommand(res.data);
          setCurrentNodeName(node.name);
          setInstallCommandModal(true);
        }
      } else {
        toast.error(res.msg || '获取安装命令失败');
      }
    } catch (error) {
      toast.error(translate("获取安装命令失败"));
    } finally {
      setNodeList(prev => prev.map(n =>
        n.id === node.id ? { ...n, copyLoading: false } : n
      ));
    }
  };

  // 手动复制安装命令
  const handleManualCopy = async () => {
    try {
      const copied = await copyText(installCommand);
      if (copied) {
        toast.success(translate("安装命令已复制到剪贴板"));
        setInstallCommandModal(false);
      } else {
        toast.error(translate("复制失败，请手动选择文本复制"));
      }
    } catch (error) {
      toast.error(translate("复制失败，请手动选择文本复制"));
    }
  };

  // 提交表单
  const handleSubmit = async () => {
    if (!validateForm()) return;

    setSubmitLoading(true);

    try {
      const apiCall = isEdit ? updateNode : createNode;
      const data = {
        ...form
      };

      const res = await apiCall(data);
      if (res.code === 0) {
        toast.success(isEdit ? translate("更新成功") : translate("创建成功"));
        setDialogVisible(false);

        if (isEdit) {
          setNodeList(prev => prev.map(n =>
            n.id === form.id ? {
              ...n,
              name: form.name,
              groupName: form.groupName?.trim() || '',
              serverIp: form.serverIp,
              port: form.port,
              tcpListenAddr: form.tcpListenAddr,
              udpListenAddr: form.udpListenAddr,
              interfaceName: form.interfaceName,
              http: form.http,
              tls: form.tls,
              socks: form.socks
            } : n
          ));
        } else {
          loadNodes();
        }
      } else {
        toast.error(res.msg || (isEdit ? translate("更新失败") : translate("创建失败")));
      }
    } catch (error) {
      toast.error(translate("网络错误，请重试"));
    } finally {
      setSubmitLoading(false);
    }
  };

  // 重置表单
  const resetForm = () => {
    setForm({
      id: null,
      name: '',
      groupName: '',
      serverIp: '',
      port: '1000-65535',
      tcpListenAddr: '[::]',
      udpListenAddr: '[::]',
      interfaceName: '',
      http: 0,
      tls: 0,
      socks: 0
    });
    setErrors({});
  };

  return (

      <div className="px-3 lg:px-6 py-8">
        <div role="tablist" aria-label={translate("节点监控视图")} className="flex gap-2 mb-5">
          <Button role="tab" aria-selected={tab === 'nodes'} size="sm" variant={tab === 'nodes' ? 'solid' : 'flat'} color={tab === 'nodes' ? 'primary' : 'default'} onPress={() => setTab('nodes')}>{translate("转发节点")}</Button>
          <Button role="tab" aria-selected={tab === 'probes'} size="sm" variant={tab === 'probes' ? 'solid' : 'flat'} color={tab === 'probes' ? 'primary' : 'default'} onPress={() => setTab('probes')}>{translate("服务器探针")}</Button>
        </div>
        {tab === 'probes' ? <ServerProbes onActivated={() => { setTab('nodes'); void loadNodes(); }} /> : <>
        {/* 页面头部 */}
        <div className="flex flex-wrap items-center justify-between gap-3 mb-6">
        <div className="flex-1">
          <Select aria-label={translate("节点分组筛选")} label={translate("节点分组筛选")} size="sm" className="max-w-xs" selectedKeys={[groupFilter]} onSelectionChange={keys => {
            const value = Array.from(keys)[0]; if (value !== undefined) setGroupFilter(String(value));
          }}>
            {[{ id: 'all', name: translate("全部节点") }, ...nodeGroups.map(group => ({ id: `group:${group.name}`, name: group.name }))].map(group => <SelectItem key={group.id}>{group.name}</SelectItem>)}
          </Select>
        </div>

        <Button size="sm" variant="bordered" onPress={() => setGroupModalOpen(true)}>{translate("管理分组")}</Button>
        <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={handleAdd}

            >{translate("新增")}</Button>

        </div>

        <div className="flex flex-wrap items-center gap-3 mb-4 text-sm">
          <label className="flex items-center gap-2"><input type="checkbox" aria-label={translate("全选可管理节点")} checked={visibleNodes.some(n => n.manageable !== false) && visibleNodes.filter(n => n.manageable !== false).every(n => selectedNodes.includes(n.id))}
            onChange={event => { const ids = visibleNodes.filter(n => n.manageable !== false).map(n => n.id); setSelectedNodes(previous => event.target.checked ? [...new Set([...previous, ...ids])] : previous.filter(id => !ids.includes(id))); }} />{translate("全选")}</label>
          {!!selectedNodes.length && <>
            <span>{translate("已选择")}{selectedNodes.length}</span>
            <Select aria-label={translate("批量加入分组")} className="w-44" size="sm" selectedKeys={batchGroup ? [batchGroup] : []} placeholder={translate("选择分组")} onSelectionChange={keys => { const value = Array.from(keys)[0]; if (value !== undefined) setBatchGroup(String(value)); }}>
              {nodeGroups.map(group => <SelectItem key={group.name}>{group.name}</SelectItem>)}
            </Select>
            <Button size="sm" isDisabled={!batchGroup || groupBusy} onPress={() => changeGroup(selectedNodes, batchGroup)}>{translate("加入分组")}</Button>
            <Button size="sm" variant="flat" isDisabled={groupBusy} onPress={() => changeGroup(selectedNodes, '')}>{translate("移出分组")}</Button>
          </>}
        </div>
        <Modal isOpen={groupModalOpen} onOpenChange={setGroupModalOpen} size="md" scrollBehavior="inside">
          <ModalContent><ModalHeader>{translate("管理分组")}</ModalHeader><ModalBody>
            <div className="flex gap-2"><Input aria-label={translate("分组名称")} placeholder={translate("分组名称")} maxLength={64} value={newGroupName} onValueChange={setNewGroupName} />
              <Button isDisabled={groupBusy || !newGroupName.trim()} onPress={async () => { setGroupBusy(true); try { const result = await createNodeGroup(newGroupName.trim()); if (result.code !== 0) return toast.error(result.msg); setNewGroupName(''); await loadGroups(); } finally { setGroupBusy(false); } }}>{translate("新增")}</Button></div>
            {nodeGroups.map(group => <div key={group.name} className="flex items-center justify-between gap-2 border-b border-divider py-2"><span>{group.name} ({group.nodeCount})</span>
              <Button size="sm" color="danger" variant="light" isDisabled={groupBusy} onPress={async () => {
                if (!window.confirm(translate('删除分组仅解除节点归属，不会删除节点，是否继续？'))) return;
                setGroupBusy(true); try { const result = await deleteNodeGroup(group.name); if (result.code !== 0) return toast.error(result.msg);
                  setNodeList(previous => previous.map(node => node.groupName === group.name && node.manageable !== false ? { ...node, groupName: '' } : node));
                  if (groupFilter === `group:${group.name}`) setGroupFilter('all'); if (batchGroup === group.name) setBatchGroup(''); await loadGroups();
                } finally { setGroupBusy(false); }
              }}>{translate("删除")}</Button></div>)}
          </ModalBody><ModalFooter><Button variant="light" onPress={() => setGroupModalOpen(false)}>{translate("关闭")}</Button></ModalFooter></ModalContent>
        </Modal>

        {/* 节点列表 */}
        {loading ? (
          <div className="flex items-center justify-center h-64">
            <div className="flex items-center gap-3">
              <Spinner size="sm" />
              <span className="text-default-600">{translate("正在加载...")}</span>
            </div>
          </div>
        ) : visibleNodes.length === 0 ? (
          <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
            <CardBody className="text-center py-16">
              <div className="flex flex-col items-center gap-4">
                <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                  <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M5 12h14M5 12l4-4m-4 4l4 4" />
                  </svg>
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-foreground">{translate("暂无节点配置")}</h3>
                  <p className="text-default-500 text-sm mt-1">{translate("还没有创建任何节点配置，点击上方按钮开始创建")}</p>
                </div>
              </div>
            </CardBody>
          </Card>
        ) : (
          <div className={serverCardGrid}>
            {visibleNodes.map((node) => (
              <ServerCard key={node.id} node={node}
                selection={<input type="checkbox" aria-label={`${translate('选择节点')} ${node.name}`} disabled={node.manageable === false || groupBusy} checked={selectedNodes.includes(node.id)} onChange={event => setSelectedNodes(previous => event.target.checked ? [...previous, node.id] : previous.filter(id => id !== node.id))} />}
                latency={<NodeLatency key={`${node.id}:${node.detectedIpv4 || node.serverIp}`} id={node.id} />}
                actions={<>                  {/* 操作按钮 */}
                  <div className="space-y-1.5">
                    <div className="flex gap-1.5">
                      <Button
                        size="sm"
                        variant="flat"
                        color="success"
                        onPress={() => handleCopyInstallCommand(node)}
                        isLoading={node.copyLoading}
                        isDisabled={node.manageable === false}
                        className="flex-1 min-h-8"
                      >{translate("安装")}</Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="primary"
                        onPress={() => handleEdit(node)}
                        isDisabled={node.manageable === false}
                        className="flex-1 min-h-8"
                      >{translate("编辑")}</Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="danger"
                        onPress={() => handleDelete(node)}
                        isDisabled={node.manageable === false}
                        className="flex-1 min-h-8"
                      >{translate("删除")}</Button>
                    </div>
                  </div></>}
              />
            ))}
          </div>
        )}

        {/* 新增/编辑节点对话框 */}
        <Modal
          isOpen={dialogVisible}
          onClose={() => setDialogVisible(false)}
          size="2xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            <ModalHeader>{dialogTitle}</ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <Input
                  label={translate("节点名称")}
                  placeholder={translate("请输入节点名称")}
                  value={form.name}
                  onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                  isInvalid={!!errors.name}
                  errorMessage={errors.name}
                  variant="bordered"
                />

                <Select label={translate("节点分组")} selectedKeys={[form.groupName ? `group:${form.groupName}` : 'none']} onSelectionChange={keys => { const value = Array.from(keys)[0]; if (value !== undefined) setForm(previous => ({ ...previous, groupName: String(value) === 'none' ? '' : String(value).slice(6) })); }}
                  items={[{id:'none',name:translate('不加入分组')}, ...Array.from(new Set([...nodeGroups.map(g => g.name), ...(form.groupName ? [form.groupName] : [])])).map(name => ({id:`group:${name}`,name}))]}>
                  {group => <SelectItem key={group.id}>{group.name}</SelectItem>}
                </Select>
                <Input
                  label={translate("服务器地址（可留空，Agent 连接后自动获取）")}
                  placeholder={translate("可留空，或填写 IP / 域名作为获取前的地址")}
                  value={form.serverIp}
                  onChange={(e) => setForm(prev => ({ ...prev, serverIp: e.target.value }))}
                  isInvalid={!!errors.serverIp}
                  errorMessage={errors.serverIp}
                  variant="bordered"
                />

                <Input
                  label={translate("可用端口")}
                  placeholder={translate("例如: 80,443,1000-65535")}
                  value={form.port}
                  onChange={(e) => setForm(prev => ({ ...prev, port: e.target.value }))}
                  isInvalid={!!errors.port}
                  errorMessage={errors.port}
                  variant="bordered"
                  description={translate("支持单个端口(80)、多个端口(80,443)或端口范围(1000-65535)，多个可用逗号分隔")}
                  classNames={{
                    input: "font-mono"
                  }}
                />

                {/* 高级配置 */}
                <Accordion variant="bordered">
                  <AccordionItem
                    key="advanced"
                    aria-label={translate("高级配置")}
                    title={translate("高级配置")}
                  >
                    <div className="space-y-4 pb-2">
                      <Input
                        label={translate("出口网卡名或IP")}
                        placeholder={translate("请输入出口网卡名或IP")}
                        value={form.interfaceName}
                        onChange={(e) => setForm(prev => ({ ...prev, interfaceName: e.target.value }))}
                        isInvalid={!!errors.interfaceName}
                        errorMessage={errors.interfaceName}
                        variant="bordered"
                        description={translate("用于多IP服务器指定使用那个IP请求远程地址，不懂的默认为空就行")}
                      />

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <Input
                          label={translate("TCP监听地址")}
                          placeholder={translate("请输入TCP监听地址")}
                          value={form.tcpListenAddr}
                          onChange={(e) => setForm(prev => ({ ...prev, tcpListenAddr: e.target.value }))}
                          isInvalid={!!errors.tcpListenAddr}
                          errorMessage={errors.tcpListenAddr}
                          variant="bordered"
                          startContent={
                            <div className="pointer-events-none flex items-center">
                              <span className="text-default-400 text-small">TCP</span>
                            </div>
                          }
                        />

                        <Input
                          label={translate("UDP监听地址")}
                          placeholder={translate("请输入UDP监听地址")}
                          value={form.udpListenAddr}
                          onChange={(e) => setForm(prev => ({ ...prev, udpListenAddr: e.target.value }))}
                          isInvalid={!!errors.udpListenAddr}
                          errorMessage={errors.udpListenAddr}
                          variant="bordered"
                          startContent={
                            <div className="pointer-events-none flex items-center">
                              <span className="text-default-400 text-small">UDP</span>
                            </div>
                          }
                        />
                      </div>
                      {/* 屏蔽协议 */}
                      <div>
                        <div className="text-sm font-medium text-default-700 mb-2">{translate("屏蔽协议")}</div>
                        <div className="text-xs text-default-500 mb-2">{translate("开启开关以屏蔽对应协议")}</div>
                        {protocolDisabled && (
                          <Alert
                            color="warning"
                            variant="flat"
                            description={protocolDisabledReason || '等待节点上线后再设置'}
                            className="mb-2"
                          />
                        )}
                        <div className={`grid grid-cols-1 sm:grid-cols-3 gap-3 bg-default-50 dark:bg-default-100 p-3 rounded-md border border-default-200 dark:border-default-100/30 ${protocolDisabled ? 'opacity-70' : ''}`}>
                          {/* HTTP tile */}
                          <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                            <div className="flex items-center gap-2 mb-2">
                              <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M2 10h20"/></svg>
                              <div className="text-sm font-medium text-default-700">HTTP</div>
                            </div>
                            <div className="flex items-center justify-between">
                              <div className="text-xs text-default-500">{translate("禁用/启用")}</div>
                              <Switch
                                size="sm"
                                isSelected={form.http === 1}
                                isDisabled={protocolDisabled}
                                onValueChange={(v) => setForm(prev => ({ ...prev, http: v ? 1 : 0 }))}
                              />
                            </div>
                            <div className="mt-1 text-xs text-default-400">{form.http === 1 ? translate("已开启") : translate("已关闭")}</div>
                          </div>

                          {/* TLS tile */}
                          <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                            <div className="flex items-center gap-2 mb-2">
                              <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M6 10V7a6 6 0 1 1 12 0v3"/><rect x="4" y="10" width="16" height="10" rx="2"/></svg>
                              <div className="text-sm font-medium text-default-700">TLS</div>
                            </div>
                            <div className="flex items-center justify-between">
                              <div className="text-xs text-default-500">{translate("禁用/启用")}</div>
                              <Switch
                                size="sm"
                                isSelected={form.tls === 1}
                                isDisabled={protocolDisabled}
                                onValueChange={(v) => setForm(prev => ({ ...prev, tls: v ? 1 : 0 }))}
                              />
                            </div>
                            <div className="mt-1 text-xs text-default-400">{form.tls === 1 ? translate("已开启") : translate("已关闭")}</div>
                          </div>

                          {/* SOCKS tile */}
                          <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                            <div className="flex items-center gap-2 mb-2">
                              <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                              <div className="text-sm font-medium text-default-700">SOCKS</div>
                            </div>
                            <div className="flex items-center justify-between">
                              <div className="text-xs text-default-500">{translate("禁用/启用")}</div>
                              <Switch
                                size="sm"
                                isSelected={form.socks === 1}
                                isDisabled={protocolDisabled}
                                onValueChange={(v) => setForm(prev => ({ ...prev, socks: v ? 1 : 0 }))}
                              />
                            </div>
                            <div className="mt-1 text-xs text-default-400">{form.socks === 1 ? translate("已开启") : translate("已关闭")}</div>
                          </div>
                        </div>
                      </div>

                      <Alert
                        color="danger"
                        variant="flat"
                        description={translate("请不要在出口节点执行屏蔽协议，否则可能影响转发；屏蔽协议仅需在入口节点执行。")}
                      />
                    </div>
                  </AccordionItem>
                </Accordion>

                <Alert
                  color="primary"
                  variant="flat"
                  description={translate("服务器ip是你要添加的服务器的ip地址，不是面板的ip地址。")}
                  className="mt-4"
                />

              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="flat"
                onPress={() => setDialogVisible(false)}
              >{translate("取消")}</Button>
              <Button
                color="primary"
                onPress={handleSubmit}
                isLoading={submitLoading}
              >
                {submitLoading ? translate("提交中...") : translate("确定")}
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 删除确认模态框 */}
        <Modal
          isOpen={deleteModalOpen}
          onOpenChange={setDeleteModalOpen}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">{translate("确认删除")}</h2>
                </ModalHeader>
                <ModalBody>
                  <p>{translate("确定要删除节点")}<strong>"{nodeToDelete?.name}"</strong>{translate("吗？")}</p>
                  <p className="text-small text-default-500">{translate("此操作不可恢复，请谨慎操作。")}</p>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>{translate("取消")}</Button>
                  <Button
                    color="danger"
                    onPress={confirmDelete}
                    isLoading={deleteLoading}
                  >
                    {deleteLoading ? translate("删除中...") : translate("确认删除")}
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 安装命令模态框 */}
        <Modal
          isOpen={installCommandModal}
          onClose={() => setInstallCommandModal(false)}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader>{translate("安装命令 -")}{currentNodeName}</ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <p className="text-sm text-default-600">{translate("请复制以下安装命令到服务器上执行：")}</p>
                <div className="relative">
                  <Textarea
                    value={installCommand}
                    readOnly
                    variant="bordered"
                    minRows={6}
                    maxRows={10}
                    className="font-mono text-sm"
                    classNames={{
                      input: "font-mono text-sm"
                    }}
                  />
                  <Button
                    size="sm"
                    color="primary"
                    variant="flat"
                    className="absolute top-2 right-2"
                    onPress={handleManualCopy}
                  >{translate("复制")}</Button>
                </div>
                <div className="text-xs text-default-500">{translate("💡 提示：如果复制按钮失效，请手动选择上方文本进行复制")}</div>
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="flat"
                onPress={() => setInstallCommandModal(false)}
              >{translate("关闭")}</Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
        </>}
      </div>

  );
}
