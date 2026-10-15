import { useState, useEffect, useRef } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Textarea } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import { Alert } from "@heroui/alert";
import { Accordion, AccordionItem } from "@heroui/accordion";
import toast from 'react-hot-toast';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  rectSortingStrategy,
} from '@dnd-kit/sortable';
import {
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';


import { 
  createForward, 
  importForwardRules,
  getForwardList, 
  updateForward, 
  deleteForward,
  forceDeleteForward,
  userTunnel, 
  pauseForwardService,
  resumeForwardService,
  diagnoseForward,
  updateForwardOrder,
  getNodeList,
  getTlsInbounds, createTlsInbound, updateTlsInbound, toggleTlsInbound, saveTlsPolicy, saveNodeTlsPolicy, deleteTlsInbound,
  getForwardGroups,
  createForwardGroup,
  updateForwardGroup,
  deleteForwardGroup,
  batchForward,
  getForwardStats,
  clearForwardTraffic
} from "@/api";
import { JwtUtil } from "@/utils/jwt";
import { copyText } from "@/utils/clipboard";
import { parseRuleTransfer } from "@/utils/rule-transfer";

interface Forward {
  id: number;
  name: string;
  tunnelId: number;
  tunnelName: string;
  inIp: string;
  inPort: number;
  remoteAddr: string;
  interfaceName?: string;
  strategy: string;
  status: number;
  inFlow: number;
  outFlow: number;
  serviceRunning: boolean;
  createdTime: string;
  userName?: string;
  userId?: number;
  inx?: number;
  protocol?: "tcp" | "udp" | "tcp_udp";
  tlsInboundId?: number;
  sniNames?: string;
  originTls?: number;
  originServerName?: string;
  originRootCa?: string;
  originAlpn?: string;
  proxyAccept?: number;
  proxySend?: number;
  proxyTrustedCidrs?: string;
  groupId?: number | null;
  trafficBytes?: number;
}

interface Tunnel {
  id: number;
  name: string;
  inNodePortSta?: number;
  inNodePortEnd?: number;
}

interface ForwardForm {
  id?: number;
  userId?: number;
  name: string;
  tunnelId: number | null;
  inPort: number | null;
  remoteAddr: string;
  interfaceName?: string;
  strategy: string;
  protocol: "tcp" | "udp" | "tcp_udp";
  tlsInboundId: number;
  sniNames: string;
  originTls: number;
  originServerName: string;
  originRootCa: string;
  originAlpn: string;
  proxyAccept: number;
  proxySend: number;
  proxyTrustedCidrs: string;
  groupId: number;
}

interface ForwardGroup {
  id: number;
  userId: number;
  name: string;
  ruleCount: number;
}

interface ForwardHour {
  hourStart: number;
  bytes: number;
}

interface AddressItem {
  id: number;
  address: string;
  copying: boolean;
}

interface DiagnosisResult {
  forwardName: string;
  timestamp: number;
  results: Array<{
    success: boolean;
    description: string;
    nodeName: string;
    nodeId: string;
    targetIp: string;
    targetPort?: number;
    message?: string;
    averageTime?: number;
    packetLoss?: number;
    fromChainType?: number; // 1: 入口, 2: 链, 3: 出口
    fromInx?: number;
    toChainType?: number;
    toInx?: number;
  }>;
}

// 添加分组接口
interface UserGroup {
  userId: number | null;
  userName: string;
  tunnelGroups: TunnelGroup[];
}

interface TunnelGroup {
  tunnelId: number;
  tunnelName: string;
  forwards: Forward[];
}

export default function ForwardPage() {
  const [loading, setLoading] = useState(true);
  const [forwards, setForwards] = useState<Forward[]>([]);
  const [tunnels, setTunnels] = useState<Tunnel[]>([]);
  const [tlsInbounds, setTlsInbounds] = useState<{id: number; tunnelId: number; name: string; port: number; allowedSni: string; enabled: number; policy: string}[]>([]);
  const [tlsPolicyId, setTlsPolicyId] = useState(0);
  const [tlsPolicy, setTlsPolicy] = useState({ proxyAccept: 0, trustedCidrs: "", defaultRuleId: "0", rejectEmpty: true, unknownDefault: false, maxHandshakes: "128", timeoutSeconds: "5" });
  const [tlsNodes, setTlsNodes] = useState<{id: number; name: string; tlsOnly?: number}[]>([]);
  const [tlsNodeId, setTlsNodeId] = useState("");
  const [tlsModalOpen, setTlsModalOpen] = useState(false);
  const [tlsDraft, setTlsDraft] = useState({ id: 0, name: "", tunnelId: "", port: "443", allowedSni: "" });
  const [ruleGroups, setRuleGroups] = useState<ForwardGroup[]>([]);
  const [groupName, setGroupName] = useState("");
  const [groupModalOpen, setGroupModalOpen] = useState(false);
  const [ruleView, setRuleView] = useState<"table" | "cards">("table");
  const [ruleQuery, setRuleQuery] = useState("");
  const [activeGroupId, setActiveGroupId] = useState(-1);
  const [rulePage, setRulePage] = useState(1);
  const [rulePageSize, setRulePageSize] = useState(20);
  const [statsOpen, setStatsOpen] = useState(false);
  const [detailForward, setDetailForward] = useState<Forward | null>(null);
  const [statsTitle, setStatsTitle] = useState("最近 24 小时流量");
  const [ruleHours, setRuleHours] = useState<ForwardHour[]>([]);
  const [batchGroupId, setBatchGroupId] = useState(0);
  const [batchTunnelId, setBatchTunnelId] = useState<number | null>(null);
  
  // 检测是否为移动端
  const [isMobile, setIsMobile] = useState(false);
  
  useEffect(() => {
    const checkMobile = () => {
      setIsMobile(window.innerWidth < 768);
    };
    
    checkMobile();
    window.addEventListener('resize', checkMobile);
    
    return () => window.removeEventListener('resize', checkMobile);
  }, []);
  
  // 显示模式状态 - 从localStorage读取，默认为平铺显示
  const [viewMode, setViewMode] = useState<'grouped' | 'direct'>(() => {
    try {
      const savedMode = localStorage.getItem('forward-view-mode');
      return (savedMode as 'grouped' | 'direct') || 'direct';
    } catch {
      return 'direct';
    }
  });
  
  // 拖拽排序相关状态
  const [forwardOrder, setForwardOrder] = useState<number[]>([]);
  
  // 模态框状态
  const [modalOpen, setModalOpen] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [batchDeleteModalOpen, setBatchDeleteModalOpen] = useState(false);
  const [addressModalOpen, setAddressModalOpen] = useState(false);
  const [diagnosisModalOpen, setDiagnosisModalOpen] = useState(false);
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [batchDeleteLoading, setBatchDeleteLoading] = useState(false);
  const [diagnosisLoading, setDiagnosisLoading] = useState(false);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedForwardIds, setSelectedForwardIds] = useState<number[]>([]);
  const [batchDeleteIds, setBatchDeleteIds] = useState<number[]>([]);
  const [forwardToDelete, setForwardToDelete] = useState<Forward | null>(null);
  const [currentDiagnosisForward, setCurrentDiagnosisForward] = useState<Forward | null>(null);
  const [diagnosisResult, setDiagnosisResult] = useState<DiagnosisResult | null>(null);
  const [addressModalTitle, setAddressModalTitle] = useState('');
  const [addressList, setAddressList] = useState<AddressItem[]>([]);
  
  // 导出相关状态
  const [exportModalOpen, setExportModalOpen] = useState(false);
  const [exportData, setExportData] = useState('');
  const [exportFormat, setExportFormat] = useState<"legacy" | "json" | "nyanpass">("json");
  const [exportLoading, setExportLoading] = useState(false);
  const [selectedTunnelForExport, setSelectedTunnelForExport] = useState<number | null>(null);
  const exportTextareaRef = useRef<HTMLTextAreaElement | null>(null);
  
  // 导入相关状态
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importData, setImportData] = useState('');
  const [importLoading, setImportLoading] = useState(false);
  const [selectedTunnelForImport, setSelectedTunnelForImport] = useState<number | null>(null);
  const [importResults, setImportResults] = useState<Array<{
    line: string;
    success: boolean;
    message: string;
    forwardName?: string;
  }>>([]);
  
  // 表单状态
  const [form, setForm] = useState<ForwardForm>({
    name: '',
    tunnelId: null,
    inPort: null,
    remoteAddr: '',
    interfaceName: '',
    strategy: 'fifo',
    protocol: 'tcp',
    tlsInboundId: 0, sniNames: "",
    originTls: 0, originServerName: "", originRootCa: "", originAlpn: "",
    proxyAccept: 0,
    proxySend: 0,
    proxyTrustedCidrs: '',
    groupId: 0
  });
  
  // 表单验证错误
  const [errors, setErrors] = useState<{[key: string]: string}>({});

  useEffect(() => {
    loadData();
  }, []);

  // 切换显示模式并保存到localStorage
  const handleViewModeChange = () => {
    if (selectionMode) {
      setSelectionMode(false);
      setSelectedForwardIds([]);
    }

    const newMode = viewMode === 'grouped' ? 'direct' : 'grouped';
    setViewMode(newMode);
    try {
      localStorage.setItem('forward-view-mode', newMode);
      
      // 切换到直接显示模式时，初始化拖拽排序顺序
      if (newMode === 'direct') {
        // 在平铺模式下，只对当前用户的转发进行排序
        const currentUserId = JwtUtil.getUserIdFromToken();
        let userForwards = forwards;
        if (currentUserId !== null) {
          userForwards = forwards.filter((f: Forward) => f.userId === currentUserId);
        }
        
        // 检查数据库中是否有排序信息
        const hasDbOrdering = userForwards.some((f: Forward) => f.inx !== undefined && f.inx !== 0);
        
        if (hasDbOrdering) {
          // 使用数据库中的排序信息
          const dbOrder = userForwards
            .sort((a: Forward, b: Forward) => (a.inx ?? 0) - (b.inx ?? 0))
            .map((f: Forward) => f.id);
          setForwardOrder(dbOrder);
          
          // 同步到localStorage
          try {
            localStorage.setItem('forward-order', JSON.stringify(dbOrder));
          } catch (error) {
            console.warn('无法保存排序到localStorage:', error);
          }
        } else {
          // 使用本地存储的顺序
          const savedOrder = localStorage.getItem('forward-order');
          if (savedOrder) {
            try {
              const orderIds = JSON.parse(savedOrder);
              const validOrder = orderIds.filter((id: number) => 
                userForwards.some((f: Forward) => f.id === id)
              );
              userForwards.forEach((forward: Forward) => {
                if (!validOrder.includes(forward.id)) {
                  validOrder.push(forward.id);
                }
              });
              setForwardOrder(validOrder);
            } catch {
              setForwardOrder(userForwards.map((f: Forward) => f.id));
            }
          } else {
            setForwardOrder(userForwards.map((f: Forward) => f.id));
          }
        }
      }
    } catch (error) {
      console.warn('无法保存显示模式到localStorage:', error);
    }
  };

  // 加载所有数据
  const loadData = async (lod = true) => {
    setLoading(lod);
    try {
      const [forwardsRes, tunnelsRes, groupsRes, tlsRes] = await Promise.all([
        getForwardList(),
        userTunnel(),
        getForwardGroups(),
        getTlsInbounds()
      ]);
      
      if (forwardsRes.code === 0) {
        const forwardsData = forwardsRes.data?.map((forward: any) => ({
          ...forward,
          serviceRunning: forward.status === 1
        })) || [];
        setForwards(forwardsData);
        
        // 初始化拖拽排序顺序
        if (viewMode === 'direct') {
          // 在平铺模式下，只对当前用户的转发进行排序
          const currentUserId = JwtUtil.getUserIdFromToken();
          let userForwards = forwardsData;
          if (currentUserId !== null) {
            userForwards = forwardsData.filter((f: Forward) => f.userId === currentUserId);
          }
          
          // 检查数据库中是否有排序信息
          const hasDbOrdering = userForwards.some((f: Forward) => f.inx !== undefined && f.inx !== 0);
          
          if (hasDbOrdering) {
            // 使用数据库中的排序信息
            const dbOrder = userForwards
              .sort((a: Forward, b: Forward) => (a.inx ?? 0) - (b.inx ?? 0))
              .map((f: Forward) => f.id);
            setForwardOrder(dbOrder);
            
            // 同步到localStorage
            try {
              localStorage.setItem('forward-order', JSON.stringify(dbOrder));
            } catch (error) {
              console.warn('无法保存排序到localStorage:', error);
            }
          } else {
            // 使用本地存储的顺序
            const savedOrder = localStorage.getItem('forward-order');
            if (savedOrder) {
              try {
                const orderIds = JSON.parse(savedOrder);
                // 验证保存的顺序是否仍然有效（只包含当前用户的转发）
                const validOrder = orderIds.filter((id: number) => 
                  userForwards.some((f: Forward) => f.id === id)
                );
                // 添加新的转发ID（如果存在）
                userForwards.forEach((forward: Forward) => {
                  if (!validOrder.includes(forward.id)) {
                    validOrder.push(forward.id);
                  }
                });
                setForwardOrder(validOrder);
              } catch {
                setForwardOrder(userForwards.map((f: Forward) => f.id));
              }
            } else {
              setForwardOrder(userForwards.map((f: Forward) => f.id));
            }
          }
        }
      } else {
        toast.error(forwardsRes.msg || '获取转发列表失败');
      }
      
      if (tunnelsRes.code === 0) {
        setTunnels(tunnelsRes.data || []);
      } else {
        console.warn('获取隧道列表失败:', tunnelsRes.msg);
      }
      if (tlsRes.code === 0) setTlsInbounds(tlsRes.data || []);
      if (groupsRes.code === 0) {
        setRuleGroups(groupsRes.data || []);
      }
    } catch (error) {
      console.error('加载数据失败:', error);
      toast.error('加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  // 按用户和隧道分组转发数据
  const groupForwardsByUserAndTunnel = (): UserGroup[] => {
    const userMap = new Map<string, UserGroup>();
    
    // 获取排序后的转发列表
    const sortedForwards = getSortedForwards();
    
    sortedForwards.forEach(forward => {
      const userKey = forward.userId ? forward.userId.toString() : 'unknown';
      const userName = forward.userName || '未知用户';
      
      if (!userMap.has(userKey)) {
        userMap.set(userKey, {
          userId: forward.userId || null,
          userName,
          tunnelGroups: []
        });
      }
      
      const userGroup = userMap.get(userKey)!;
      let tunnelGroup = userGroup.tunnelGroups.find(tg => tg.tunnelId === forward.tunnelId);
      
      if (!tunnelGroup) {
        tunnelGroup = {
          tunnelId: forward.tunnelId,
          tunnelName: forward.tunnelName,
          forwards: []
        };
        userGroup.tunnelGroups.push(tunnelGroup);
      }
      
      tunnelGroup.forwards.push(forward);
    });
    
    // 排序：先按用户名，再按隧道名
    const result = Array.from(userMap.values());
    result.sort((a, b) => a.userName.localeCompare(b.userName));
    result.forEach(userGroup => {
      userGroup.tunnelGroups.sort((a, b) => a.tunnelName.localeCompare(b.tunnelName));
    });
    
    return result;
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: {[key: string]: string} = {};
    
    if (!form.name.trim()) {
      newErrors.name = '请输入转发名称';
    } else if (form.name.length < 2 || form.name.length > 50) {
      newErrors.name = '转发名称长度应在2-50个字符之间';
    }
    
    if (!form.tunnelId) {
      newErrors.tunnelId = '请选择关联隧道';
    }
    
    // 验证入口端口（可选，如果填写则验证）
    if (form.inPort !== null && form.inPort !== undefined) {
      const port = Number(form.inPort);
      if (isNaN(port) || port < 1 || port > 65535) {
        newErrors.inPort = '端口必须在 1-65535 之间';
      }
    }
    
    if (!form.remoteAddr.trim()) {
      newErrors.remoteAddr = '请输入远程地址';
    } else {
      // 验证地址格式
      const addresses = form.remoteAddr.split('\n').map(addr => addr.trim()).filter(addr => addr);
      const ipv4Pattern = /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?):\d+$/;
      const ipv6FullPattern = /^\[((([0-9a-fA-F]{1,4}:){7}([0-9a-fA-F]{1,4}|:))|(([0-9a-fA-F]{1,4}:){6}(:[0-9a-fA-F]{1,4}|((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9a-fA-F]{1,4}:){5}(((:[0-9a-fA-F]{1,4}){1,2})|:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9a-fA-F]{1,4}:){4}(((:[0-9a-fA-F]{1,4}){1,3})|((:[0-9a-fA-F]{1,4})?:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){3}(((:[0-9a-fA-F]{1,4}){1,4})|((:[0-9a-fA-F]{1,4}){0,2}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){2}(((:[0-9a-fA-F]{1,4}){1,5})|((:[0-9a-fA-F]{1,4}){0,3}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){1}(((:[0-9a-fA-F]{1,4}){1,6})|((:[0-9a-fA-F]{1,4}){0,4}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(:(((:[0-9a-fA-F]{1,4}){1,7})|((:[0-9a-fA-F]{1,4}){0,5}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:)))\]:\d+$/;
      const domainPattern = /^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)*:\d+$/;
      
      for (let i = 0; i < addresses.length; i++) {
        const addr = addresses[i];
        if (!ipv4Pattern.test(addr) && !ipv6FullPattern.test(addr) && !domainPattern.test(addr)) {
          newErrors.remoteAddr = `第${i + 1}行地址格式错误`;
          break;
        }
      }
    }
    
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 新增转发
  const handleAdd = () => {
    setIsEdit(false);
    setForm({
      name: '',
      tunnelId: null,
      inPort: null,
      remoteAddr: '',
      interfaceName: '',
      strategy: 'fifo',
      protocol: 'tcp',
      tlsInboundId: 0, sniNames: "",
    originTls: 0, originServerName: "", originRootCa: "", originAlpn: "",
    proxyAccept: 0,
      proxySend: 0,
      proxyTrustedCidrs: '',
      groupId: 0
    });
    setErrors({});
    setModalOpen(true);
  };

  const handleCreateGroup = async () => {
    const name = groupName.trim();
    if (!name) return toast.error("请输入分组名称");
    const result = await createForwardGroup(name);
    if (result.code !== 0) return toast.error(result.msg || "创建分组失败");
    toast.success("分组已创建");
    setGroupName("");
    setGroupModalOpen(false);
    await loadData(false);
  };

  const handleBatchMeta = async (action: "group" | "clear" | "enable" | "disable", groupId?: number) => {
    const ids = selectedForwardIds.filter(id => tableRules.some(row => row.id === id));
    if (!ids.length) return toast.error("请先选择规则");
    if (action === "clear" && !window.confirm("清空所选规则的展示流量？用户已消耗额度不会返还。")) return;
    const result = await batchForward({ ids, action, groupId });
    if (result.code !== 0) return toast.error(result.msg || "批量操作失败");
    toast.success("操作完成");
    setSelectedForwardIds([]);
    await loadData(false);
  };

  const handleBatchSwitch = async () => {
    const ids = selectedForwardIds.filter(id => tableRules.some(row => row.id === id));
    if (!ids.length || !batchTunnelId) return toast.error("请选择规则和目标隧道");
    if (!window.confirm("切换所选规则的入口？旧连接会关闭，并重新分配监听端口。")) return;
    const result = await batchForward({ ids, action: "switch", tunnelId: batchTunnelId });
    if (result.code !== 0) return toast.error(result.msg || "批量切换失败");
    toast.success("入口切换完成");
    setSelectedForwardIds([]);
    await loadData(false);
  };

  const showForwardStats = async (forward?: Forward) => {
    const result = await getForwardStats(forward?.id);
    if (result.code !== 0) return toast.error(result.msg || "获取统计失败");
    setRuleHours(result.data || []);
    setStatsTitle(forward ? `${forward.name} · 最近 24 小时流量` : JwtUtil.getRoleIdFromToken() === 0 ? "全站最近 24 小时流量" : "最近 24 小时流量");
    setStatsOpen(true);
  };

  const clearOneForwardTraffic = async (forward: Forward) => {
    if (!window.confirm("清空此规则的展示流量？用户已消耗额度不会返还。")) return;
    const result = await clearForwardTraffic(forward.id);
    if (result.code !== 0) return toast.error(result.msg || "清空流量失败");
    await loadData(false);
  };

  // 编辑转发
  const handleEdit = (forward: Forward) => {
    setIsEdit(true);
    setForm({
      id: forward.id,
      userId: forward.userId,
      name: forward.name,
      tunnelId: forward.tunnelId,
      inPort: forward.inPort,
      remoteAddr: forward.remoteAddr.split(',').join('\n'),
      interfaceName: forward.interfaceName || '',
      strategy: forward.strategy || 'fifo',
      protocol: forward.protocol || 'tcp_udp',
      tlsInboundId: forward.tlsInboundId || 0,
      sniNames: forward.sniNames || "",
      originTls: forward.originTls || 0,
      originServerName: forward.originServerName || "",
      originRootCa: forward.originRootCa || "",
      originAlpn: forward.originAlpn || "",
      proxyAccept: forward.proxyAccept || 0,
      proxySend: forward.proxySend || 0,
      proxyTrustedCidrs: forward.proxyTrustedCidrs || '',
      groupId: forward.groupId || 0
    });
    setErrors({});
    setModalOpen(true);
  };

  const handleCopy = (forward: Forward) => {
    handleEdit(forward);
    setIsEdit(false);
    setForm(prev => ({
      ...prev,
      id: undefined,
      userId: undefined,
      name: `${forward.name} 副本`,
      inPort: null
    }));
  };

  const handleRename = async (forward: Forward) => {
    const name = window.prompt("规则名称", forward.name)?.trim();
    if (!name || name === forward.name) return;
    if (name.length < 2 || name.length > 50) return toast.error("规则名称长度应为 2–50 个字符");
    const result = await updateForward({
      id: forward.id,
      userId: forward.userId,
      name,
      tunnelId: forward.tunnelId,
      inPort: forward.inPort,
      remoteAddr: forward.remoteAddr,
      strategy: forward.strategy,
      protocol: forward.protocol || "tcp_udp",
      tlsInboundId: forward.tlsInboundId || 0,
      sniNames: forward.sniNames || "",
      originTls: forward.originTls || 0,
      originServerName: forward.originServerName || "",
      originRootCa: forward.originRootCa || "",
      originAlpn: forward.originAlpn || "",
      proxyAccept: forward.proxyAccept || 0,
      proxySend: forward.proxySend || 0,
      proxyTrustedCidrs: forward.proxyTrustedCidrs || '',
      groupId: forward.groupId || 0
    });
    if (result.code !== 0) return toast.error(result.msg || "重命名失败");
    await loadData(false);
  };

  // 显示删除确认
  const handleDelete = (forward: Forward) => {
    setForwardToDelete(forward);
    setDeleteModalOpen(true);
  };

  // 确认删除转发
  const confirmDelete = async () => {
    if (!forwardToDelete) return;
    
    setDeleteLoading(true);
    try {
      const res = await deleteForward(forwardToDelete.id);
      if (res.code === 0) {
        toast.success('删除成功');
        setDeleteModalOpen(false);
        loadData();
      } else {
        // 删除失败，询问是否强制删除
        const confirmed = window.confirm(`常规删除失败：${res.msg || '删除失败'}\n\n是否需要强制删除？\n\n⚠️ 注意：强制删除不会去验证节点端是否已经删除对应的转发服务。`);
        if (confirmed) {
          const forceRes = await forceDeleteForward(forwardToDelete.id);
          if (forceRes.code === 0) {
            toast.success('强制删除成功');
            setDeleteModalOpen(false);
            loadData();
          } else {
            toast.error(forceRes.msg || '强制删除失败');
          }
        }
      }
    } catch (error) {
      console.error('删除失败:', error);
      toast.error('删除失败');
    } finally {
      setDeleteLoading(false);
    }
  };

  const getVisibleForwards = (): Forward[] => {
    if (viewMode === 'grouped') {
      const grouped = groupForwardsByUserAndTunnel();
      return grouped.flatMap(userGroup =>
        userGroup.tunnelGroups.flatMap(tunnelGroup => tunnelGroup.forwards)
      );
    }
    return getSortedForwards();
  };

  const toggleSelectionMode = () => {
    if (selectionMode) {
      setSelectionMode(false);
      setSelectedForwardIds([]);
      return;
    }
    setSelectionMode(true);
  };

  const toggleForwardSelection = (forwardId: number) => {
    setSelectedForwardIds(prev =>
      prev.includes(forwardId)
        ? prev.filter(id => id !== forwardId)
        : [...prev, forwardId]
    );
  };

  const selectAllVisibleForwards = () => {
    const visibleIds = getVisibleForwards().map(forward => forward.id);
    setSelectedForwardIds(visibleIds);
  };

  const clearSelectedForwards = () => {
    setSelectedForwardIds([]);
  };

  const handleBatchDelete = () => {
    const ids = ruleView === "table"
      ? selectedForwardIds.filter(id => tableRules.some(row => row.id === id))
      : selectedForwardIds.filter(id => visibleForwards.some(row => row.id === id));
    if (ids.length === 0) {
      toast.error('请先选择要删除的转发');
      return;
    }
    setBatchDeleteIds(ids);
    setBatchDeleteModalOpen(true);
  };

  const confirmBatchDelete = async () => {
    if (batchDeleteIds.length === 0) return;

    setBatchDeleteLoading(true);
    try {
      const nameById = new Map(forwards.map(forward => [forward.id, forward.name]));
      let successCount = 0;
      const failedItems: string[] = [];

      for (const id of batchDeleteIds) {
        try {
          const res = await deleteForward(id);
          if (res.code === 0) {
            successCount += 1;
            continue;
          }
          failedItems.push(`${nameById.get(id) || `ID:${id}`}: ${res.msg || '删除失败'}`);
        } catch (error) {
          failedItems.push(`${nameById.get(id) || `ID:${id}`}: 网络错误`);
        }
      }

      if (successCount > 0) {
        toast.success(`批量删除完成：删除 ${successCount} 条，失败 ${failedItems.length} 条`);
      }
      if (failedItems.length > 0) {
        const preview = failedItems.slice(0, 3).join('\n');
        toast.error(`部分删除失败（${failedItems.length}条）\n${preview}`);
      }

      setBatchDeleteModalOpen(false);
      setBatchDeleteIds([]);
      setSelectionMode(false);
      setSelectedForwardIds([]);
      loadData(false);
    } finally {
      setBatchDeleteLoading(false);
    }
  };

  // 处理隧道选择变化
  const handleTunnelChange = (tunnelId: string) => {
    setForm(prev => ({ ...prev, tunnelId: parseInt(tunnelId), tlsInboundId: 0, sniNames: "" }));
  };

  // 提交表单
  const handleSubmit = async () => {
    if (!validateForm()) return;
    
    setSubmitLoading(true);
    try {
      const processedRemoteAddr = form.remoteAddr
        .split('\n')
        .map(addr => addr.trim())
        .filter(addr => addr)
        .join(',');

      const addressCount = processedRemoteAddr.split(',').length;
      
      let res;
      if (isEdit) {
        // 更新时确保包含必要字段
        const updateData = {
          id: form.id,
          userId: form.userId,
          name: form.name,
          tunnelId: form.tunnelId,
          inPort: form.inPort,
          remoteAddr: processedRemoteAddr,
          strategy: addressCount > 1 ? form.strategy : 'fifo',
          protocol: form.protocol,
          tlsInboundId: form.tlsInboundId,
          sniNames: form.sniNames,
          originTls: form.originTls,
          originServerName: form.originServerName,
          originRootCa: form.originRootCa,
          originAlpn: form.originAlpn,
          proxyAccept: form.proxyAccept,
          proxySend: form.proxySend,
          proxyTrustedCidrs: form.proxyTrustedCidrs,
          groupId: form.groupId
        };
        res = await updateForward(updateData);
      } else {
        // 创建时不需要id和userId（后端会自动设置）
        const createData = {
          name: form.name,
          tunnelId: form.tunnelId,
          inPort: form.inPort,
          remoteAddr: processedRemoteAddr,
          strategy: addressCount > 1 ? form.strategy : 'fifo',
          protocol: form.protocol,
          tlsInboundId: form.tlsInboundId,
          sniNames: form.sniNames,
          originTls: form.originTls,
          originServerName: form.originServerName,
          originRootCa: form.originRootCa,
          originAlpn: form.originAlpn,
          proxyAccept: form.proxyAccept,
          proxySend: form.proxySend,
          proxyTrustedCidrs: form.proxyTrustedCidrs,
          groupId: form.groupId
        };
        res = await createForward(createData);
      }
      
      if (res.code === 0) {
        toast.success(isEdit ? '修改成功' : '创建成功');
        setModalOpen(false);
        await loadData(false);
      } else {
        toast.error(res.msg || (isEdit ? '修改失败' : '创建失败'));
      }
    } catch (error) {
      console.error('提交失败:', error);
      toast.error(error instanceof Error ? error.message : '提交转发规则失败');
    } finally {
      setSubmitLoading(false);
    }
  };

  // 处理服务开关
  const handleServiceToggle = async (forward: Forward) => {
    if (forward.status !== 1 && forward.status !== 0) {
      toast.error('转发状态异常，无法操作');
      return;
    }

    const targetState = !forward.serviceRunning;
    
    try {
      // 乐观更新UI
      setForwards(prev => prev.map(f => 
        f.id === forward.id 
          ? { ...f, serviceRunning: targetState }
          : f
      ));

      let res;
      if (targetState) {
        res = await resumeForwardService(forward.id);
      } else {
        res = await pauseForwardService(forward.id);
      }
      
      if (res.code === 0) {
        toast.success(targetState ? '服务已启动' : '服务已暂停');
        // 更新转发状态
        setForwards(prev => prev.map(f => 
          f.id === forward.id 
            ? { ...f, status: targetState ? 1 : 0 }
            : f
        ));
      } else {
        // 操作失败，恢复UI状态
        setForwards(prev => prev.map(f => 
          f.id === forward.id 
            ? { ...f, serviceRunning: !targetState }
            : f
        ));
        toast.error(res.msg || '操作失败');
      }
    } catch (error) {
      // 操作失败，恢复UI状态
      setForwards(prev => prev.map(f => 
        f.id === forward.id 
          ? { ...f, serviceRunning: !targetState }
          : f
      ));
      console.error('服务开关操作失败:', error);
      toast.error('网络错误，操作失败');
    }
  };

  // 诊断转发
  const handleDiagnose = async (forward: Forward) => {
    setCurrentDiagnosisForward(forward);
    setDiagnosisModalOpen(true);
    setDiagnosisLoading(true);
    setDiagnosisResult(null);

    try {
      const response = await diagnoseForward(forward.id);
      if (response.code === 0) {
        setDiagnosisResult(response.data);
      } else {
        toast.error(response.msg || '诊断失败');
        setDiagnosisResult({
          forwardName: forward.name,
          timestamp: Date.now(),
          results: [{
            success: false,
            description: '诊断失败',
            nodeName: '-',
            nodeId: '-',
            targetIp: forward.remoteAddr.split(',')[0] || '-',
            message: response.msg || '诊断过程中发生错误'
          }]
        });
      }
    } catch (error) {
      console.error('诊断失败:', error);
      toast.error('网络错误，请重试');
      setDiagnosisResult({
        forwardName: forward.name,
        timestamp: Date.now(),
        results: [{
          success: false,
          description: '网络错误',
          nodeName: '-',
          nodeId: '-',
          targetIp: forward.remoteAddr.split(',')[0] || '-',
          message: '无法连接到服务器'
        }]
      });
    } finally {
      setDiagnosisLoading(false);
    }
  };

  // 获取连接质量
  const getQualityDisplay = (averageTime?: number, packetLoss?: number) => {
    if (averageTime === undefined || packetLoss === undefined) return null;
    
    if (averageTime < 30 && packetLoss === 0) return { text: '🚀 优秀', color: 'success' };
    if (averageTime < 50 && packetLoss === 0) return { text: '✨ 很好', color: 'success' };
    if (averageTime < 100 && packetLoss < 1) return { text: '👍 良好', color: 'primary' };
    if (averageTime < 150 && packetLoss < 2) return { text: '😐 一般', color: 'warning' };
    if (averageTime < 200 && packetLoss < 5) return { text: '😟 较差', color: 'warning' };
    return { text: '😵 很差', color: 'danger' };
  };

  // 格式化流量
  const formatFlow = (value: number): string => {
    if (value === 0) return '0 B';
    if (value < 1024) return value + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(2) + ' KB';
    if (value < 1024 * 1024 * 1024) return (value / (1024 * 1024)).toFixed(2) + ' MB';
    return (value / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  };

  // 格式化入口地址
  const formatInAddress = (ipString: string, port: number): string => {
    if (!ipString) return '';
    
    const items = ipString.split(',').map(item => item.trim()).filter(item => item);
    if (items.length === 0) return '';
    
    // 检查第一项是否已经包含端口（格式：IP:端口）
    const firstItem = items[0];
    const hasPort = /:\d+$/.test(firstItem);
    
    if (hasPort) {
      // inIp 已经包含完整的 IP:Port 组合
      if (items.length === 1) {
        return items[0];
      }
      return `${items[0]} (+${items.length - 1}个)`;
    }
    
    // inIp 只包含IP，需要添加端口（兼容旧数据）
    if (!port) return '';
    
    if (items.length === 1) {
      const ip = items[0];
      if (ip.includes(':') && !ip.startsWith('[')) {
        return `[${ip}]:${port}`;
      } else {
        return `${ip}:${port}`;
      }
    }
    
    const firstIp = items[0];
    let formattedFirstIp;
    if (firstIp.includes(':') && !firstIp.startsWith('[')) {
      formattedFirstIp = `[${firstIp}]`;
    } else {
      formattedFirstIp = firstIp;
    }
    
    return `${formattedFirstIp}:${port} (+${items.length - 1}个)`;
  };

  // 格式化远程地址
  const formatRemoteAddress = (addressString: string): string => {
    if (!addressString) return '';
    
    const addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
    if (addresses.length === 0) return '';
    if (addresses.length === 1) return addresses[0];
    
    return `${addresses[0]} (+${addresses.length - 1})`;
  };

  // 检查是否有多个地址
  const hasMultipleAddresses = (addressString: string): boolean => {
    if (!addressString) return false;
    const addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
    return addresses.length > 1;
  };

  // 显示地址列表弹窗
  const showAddressModal = (addressString: string, port: number | null, title: string) => {
    if (!addressString) return;
    
    let addresses: string[];
    if (port !== null) {
      // 入口地址处理
      const items = addressString.split(',').map(item => item.trim()).filter(item => item);
      if (items.length <= 1) {
        copyToClipboard(formatInAddress(addressString, port), title);
        return;
      }
      
      // 检查是否已经包含端口
      const hasPort = /:\d+$/.test(items[0]);
      if (hasPort) {
        // 已经包含完整的 IP:Port 组合，直接使用
        addresses = items;
      } else {
        // 只包含IP，需要添加端口
        addresses = items.map(ip => {
          if (ip.includes(':') && !ip.startsWith('[')) {
            return `[${ip}]:${port}`;
          } else {
            return `${ip}:${port}`;
          }
        });
      }
    } else {
      // 远程地址处理
      addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
      if (addresses.length <= 1) {
        copyToClipboard(addressString, title);
        return;
      }
    }
    
    setAddressList(addresses.map((address, index) => ({
      id: index,
      address,
      copying: false
    })));
    setAddressModalTitle(`${title} (${addresses.length}个)`);
    setAddressModalOpen(true);
  };

  // 复制到剪贴板
  const copyToClipboard = async (text: string, label: string = '内容') => {
    try {
      const copied = await copyText(text);
      if (!copied) {
        toast.error('复制失败');
        return;
      }
      toast.success(`已复制${label}`);
    } catch (error) {
      toast.error('复制失败');
    }
  };

  // 复制地址
  const copyAddress = async (addressItem: AddressItem) => {
    try {
      setAddressList(prev => prev.map(item => 
        item.id === addressItem.id ? { ...item, copying: true } : item
      ));
      await copyToClipboard(addressItem.address, '地址');
    } catch (error) {
      toast.error('复制失败');
    } finally {
      setAddressList(prev => prev.map(item => 
        item.id === addressItem.id ? { ...item, copying: false } : item
      ));
    }
  };

  // 复制所有地址
  const copyAllAddresses = async () => {
    if (addressList.length === 0) return;
    const allAddresses = addressList.map(item => item.address).join('\n');
    await copyToClipboard(allAddresses, '所有地址');
  };

    // 导出转发数据
  const handleExport = () => {
    setSelectedTunnelForExport(null);
    setExportData('');
    setExportModalOpen(true);
  };

  // 执行导出
  const executeExport = () => {
    if (!selectedTunnelForExport) {
      toast.error('请选择要导出的隧道');
      return;
    }

    setExportLoading(true);
    
    try {
      // 根据当前显示模式获取要导出的转发列表
      let forwardsToExport: Forward[] = [];
      
      if (viewMode === 'grouped') {
        // 分组模式下，获取指定隧道的转发
        const userGroups = groupForwardsByUserAndTunnel();
        forwardsToExport = userGroups.flatMap(userGroup => 
          userGroup.tunnelGroups
            .filter(tunnelGroup => tunnelGroup.tunnelId === selectedTunnelForExport)
            .flatMap(tunnelGroup => tunnelGroup.forwards)
        );
      } else {
        // 直接显示模式下，过滤指定隧道的转发
        forwardsToExport = getSortedForwards().filter(forward => forward.tunnelId === selectedTunnelForExport);
      }
      
      if (forwardsToExport.length === 0) {
        toast.error('所选隧道没有转发数据');
        setExportLoading(false);
        return;
      }
      
      // 格式化导出数据：remoteAddr|name|inPort
      const exportLines = forwardsToExport.map(forward => {
        return `${forward.remoteAddr}|${forward.name}|${forward.inPort}`;
      });
      
      let exportText = exportLines.join('\n');
      if (exportFormat === "json") {
        exportText = JSON.stringify({ format: "flux-rules", version: 1, rules: forwardsToExport.map(row => ({
          name: row.name, tunnelId: row.tunnelId, remoteAddr: row.remoteAddr, inPort: row.inPort,
          protocol: row.protocol || "tcp_udp", strategy: row.strategy || "fifo",
          groupId: row.groupId || 0, status: row.status, proxyAccept: row.proxyAccept || 0,
          proxySend: row.proxySend || 0, proxyTrustedCidrs: row.proxyTrustedCidrs || "",
          tlsInboundId: row.tlsInboundId || 0, tlsInboundPort: tlsInbounds.find(item => item.id === row.tlsInboundId)?.port || 0, sniNames: row.sniNames || "",
          originTls: row.originTls || 0, originServerName: row.originServerName || "",
          originRootCa: row.originRootCa || "", originAlpn: row.originAlpn || ""
        })) }, null, 2);
      } else if (exportFormat === "nyanpass") {
        if (forwardsToExport.some(row => row.protocol !== "tcp" || row.originTls === 1 || !!row.tlsInboundId)) {
          toast.error("nyanpass 格式仅支持无回源 TLS 的 TCP；请选完整 JSON 保留配置");
          return;
        }
        exportText = forwardsToExport.map(row => JSON.stringify({
          name: row.name, dest: row.remoteAddr.split(","), listen_port: row.inPort,
          proxy_protocol: row.proxySend === 2 ? 3 : row.proxySend || 0,
          accept_proxy_protocol: row.proxyAccept ? 1 : 0
        })).join("\n");
      }
      setExportData(exportText);
    } catch (error) {
      console.error('导出失败:', error);
      toast.error('导出失败');
    } finally {
      setExportLoading(false);
    }
  };

  // 复制导出数据
  const copyExportData = async () => {
    const content = exportData?.trim();
    if (!content) {
      toast.error('暂无可复制的导出数据');
      return;
    }

    let copied = false;

    // 导出数据优先从可见文本框复制，兼容性比隐藏 textarea 更好
    const exportTextarea = exportTextareaRef.current;
    if (exportTextarea) {
      try {
        exportTextarea.focus();
        exportTextarea.select();
        exportTextarea.setSelectionRange(0, exportTextarea.value.length);
        copied = document.execCommand('copy');
      } catch (error) {
        copied = false;
      }
    }

    if (!copied) {
      copied = await copyText(content);
    }

    if (copied) {
      toast.success('已复制转发数据');
      return;
    }

    if (exportTextarea) {
      exportTextarea.focus();
      exportTextarea.select();
      exportTextarea.setSelectionRange(0, exportTextarea.value.length);
    }
    toast.error('自动复制失败，已选中文本，请手动复制');
  };

  // 导入转发数据
  const handleImport = () => {
    setImportData('');
    setImportResults([]);
    setSelectedTunnelForImport(null);
    setImportModalOpen(true);
  };

  // 执行导入
  const executeImport = async () => {
    if (!importData.trim()) {
      toast.error('请输入要导入的数据');
      return;
    }

    if (!selectedTunnelForImport) {
      toast.error('请选择要导入的隧道');
      return;
    }

    setImportLoading(true);
    setImportResults([]); // 清空之前的结果

    try {
      if (/^[\[{]/.test(importData.trim())) {
        const rules = parseRuleTransfer(importData, selectedTunnelForImport);
        const response = await importForwardRules(rules);
        setImportResults([{ line: `JSON · ${rules.length} 条`, success: response.code === 0, message: response.msg }]);
        if (response.code !== 0) toast.error(response.msg || "导入失败");
        else toast.success(`已导入 ${rules.length} 条规则`);
        await loadData(false);
        return;
      }
      const lines = importData.trim().split('\n').filter(line => line.trim());
      
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        const parts = line.split('|');
        
        if (parts.length < 2) {
          setImportResults(prev => [{
            line,
            success: false,
            message: '格式错误：需要至少包含目标地址和转发名称'
          }, ...prev]);
          continue;
        }

        const [remoteAddr, name, inPort] = parts;
        
        if (!remoteAddr.trim() || !name.trim()) {
          setImportResults(prev => [{
            line,
            success: false,
            message: '目标地址和转发名称不能为空'
          }, ...prev]);
          continue;
        }

        // 验证远程地址格式 - 支持单个地址或多个地址用逗号分隔
        const addresses = remoteAddr.trim().split(',');
        const addressPattern = /^[^:]+:\d+$/;
        const isValidFormat = addresses.every(addr => addressPattern.test(addr.trim()));
        
        if (!isValidFormat) {
          setImportResults(prev => [{
            line,
            success: false,
            message: '目标地址格式错误，应为 地址:端口 格式，多个地址用逗号分隔'
          }, ...prev]);
          continue;
        }

        try {
          // 处理入口端口
          let portNumber: number | null = null;
          if (inPort && inPort.trim()) {
            const port = parseInt(inPort.trim());
            if (isNaN(port) || port < 1 || port > 65535) {
              setImportResults(prev => [{
                line,
                success: false,
                message: '入口端口格式错误，应为1-65535之间的数字'
              }, ...prev]);
              continue;
            }
            portNumber = port;
          }

          // 调用创建转发接口
          const response = await createForward({
            name: name.trim(),
            tunnelId: selectedTunnelForImport, // 使用用户选择的隧道
            inPort: portNumber, // 使用指定端口或自动分配
            remoteAddr: remoteAddr.trim(),
            strategy: 'fifo'
          });

          if (response.code === 0) {
            setImportResults(prev => [{
              line,
              success: true,
              message: '创建成功',
              forwardName: name.trim()
            }, ...prev]);
          } else {
            setImportResults(prev => [{
              line,
              success: false,
              message: response.msg || '创建失败'
            }, ...prev]);
          }
        } catch (error) {
          setImportResults(prev => [{
            line,
            success: false,
            message: '网络错误，创建失败'
          }, ...prev]);
        }
      }
      
      
      toast.success(`导入执行完成`);
      
      // 导入完成后刷新转发列表
      await loadData(false);
    } catch (error) {
      console.error('导入失败:', error);
      toast.error(error instanceof Error ? error.message : '导入过程中发生错误');
    } finally {
      setImportLoading(false);
    }
  };

  // 获取状态显示
  const getStatusDisplay = (status: number) => {
    switch (status) {
      case 1:
        return { color: 'success', text: '正常' };
      case 0:
        return { color: 'warning', text: '暂停' };
      case -1:
        return { color: 'danger', text: '异常' };
      default:
        return { color: 'default', text: '未知' };
    }
  };

  // 获取策略显示
  const getStrategyDisplay = (strategy: string) => {
    switch (strategy) {
      case 'fifo':
        return { color: 'primary', text: '主备' };
      case 'round':
        return { color: 'success', text: '轮询' };
      case 'rand':
        return { color: 'warning', text: '随机' };
      default:
        return { color: 'default', text: '未知' };
    }
  };

  // 获取地址数量
  const getAddressCount = (addressString: string): number => {
    if (!addressString) return 0;
    const addresses = addressString.split('\n').map(addr => addr.trim()).filter(addr => addr);
    return addresses.length;
  };

  // 处理拖拽结束
  const handleDragEnd = async (event: DragEndEvent) => {
    const { active, over } = event;
    
    if (!active || !over || active.id === over.id) return;
    
    // 确保 forwardOrder 存在且有效
    if (!forwardOrder || forwardOrder.length === 0) return;
    
    const activeId = Number(active.id);
    const overId = Number(over.id);
    
    // 检查 ID 是否有效
    if (isNaN(activeId) || isNaN(overId)) return;
    
    const oldIndex = forwardOrder.indexOf(activeId);
    const newIndex = forwardOrder.indexOf(overId);
    
    if (oldIndex !== -1 && newIndex !== -1 && oldIndex !== newIndex) {
      const newOrder = arrayMove(forwardOrder, oldIndex, newIndex);
      setForwardOrder(newOrder);
      
      // 保存到localStorage
      try {
        localStorage.setItem('forward-order', JSON.stringify(newOrder));
      } catch (error) {
        console.warn('无法保存排序到localStorage:', error);
      }
      
      // 持久化到数据库
      try {
        const forwardsToUpdate = newOrder.map((id, index) => ({
          id,
          inx: index
        }));
        
        const response = await updateForwardOrder({ forwards: forwardsToUpdate });
        if (response.code === 0) {
          // 更新本地数据中的 inx 字段
          setForwards(prev => prev.map(forward => {
            const updatedForward = forwardsToUpdate.find(f => f.id === forward.id);
            if (updatedForward) {
              return { ...forward, inx: updatedForward.inx };
            }
            return forward;
          }));
        } else {
          toast.error('保存排序失败：' + (response.msg || '未知错误'));
        }
      } catch (error) {
        console.error('保存排序到数据库失败:', error);
        toast.error('保存排序失败，请重试');
      }
    }
  };

  // 传感器配置 - 使用默认配置避免错误
  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  );

  // 根据排序顺序获取转发列表
  const getSortedForwards = (includeAllUsers = false): Forward[] => {
    // 确保 forwards 数组存在且有效
    if (!forwards || forwards.length === 0) {
      return [];
    }
    
    // 在平铺模式下，只显示当前用户的转发
    let filteredForwards = forwards;
    if (viewMode === 'direct' && !includeAllUsers) {
      const currentUserId = JwtUtil.getUserIdFromToken();
      if (currentUserId !== null) {
        filteredForwards = forwards.filter(forward => forward.userId === currentUserId);
      }
    }
    
    // 确保过滤后的转发列表有效
    if (!filteredForwards || filteredForwards.length === 0) {
      return [];
    }
    
    // 优先使用数据库中的 inx 字段进行排序
    const sortedForwards = [...filteredForwards].sort((a, b) => {
      const aInx = a.inx ?? 0;
      const bInx = b.inx ?? 0;
      return aInx - bInx;
    });
    
    // 如果数据库中没有排序信息，则使用本地存储的顺序
    if (forwardOrder && forwardOrder.length > 0 && sortedForwards.every(f => f.inx === undefined || f.inx === 0)) {
      const forwardMap = new Map(filteredForwards.map(f => [f.id, f]));
      const localSortedForwards: Forward[] = [];
      
      forwardOrder.forEach(id => {
        const forward = forwardMap.get(id);
        if (forward) {
          localSortedForwards.push(forward);
        }
      });
      
      // 添加不在排序列表中的转发（新添加的）
      filteredForwards.forEach(forward => {
        if (!forwardOrder.includes(forward.id)) {
          localSortedForwards.push(forward);
        }
      });
      
      return localSortedForwards;
    }
    
    return sortedForwards;
  };

  // 可拖拽的转发卡片组件
  const SortableForwardCard = ({ forward }: { forward: Forward }) => {
    // 确保 forward 对象有效
    if (!forward || !forward.id) {
      return null;
    }

    const {
      attributes,
      listeners,
      setNodeRef,
      transform,
      transition,
      isDragging,
    } = useSortable({ id: forward.id });

    const style = {
      transform: transform ? CSS.Transform.toString(transform) : undefined,
      transition: transition || undefined,
      opacity: isDragging ? 0.5 : 1,
    };

    return (
      <div ref={setNodeRef} style={style} {...attributes}>
        {renderForwardCard(forward, listeners)}
      </div>
    );
  };

  // 渲染转发卡片
  const renderForwardCard = (forward: Forward, listeners?: any) => {
    const statusDisplay = getStatusDisplay(forward.status);
    const strategyDisplay = getStrategyDisplay(forward.strategy);
    const isSelected = selectedForwardIds.includes(forward.id);
    
    return (
      <Card
        key={forward.id}
        className={`group shadow-sm border hover:shadow-md transition-shadow duration-200 ${
          selectionMode && isSelected ? 'border-primary ring-2 ring-primary/30' : 'border-divider'
        }`}
      >
        <CardHeader className="pb-2">
          <div className="flex justify-between items-start w-full">
            <div className="flex-1 min-w-0 flex items-start gap-2">
              {selectionMode && (
                <input
                  type="checkbox"
                  className="mt-1 h-4 w-4 cursor-pointer"
                  checked={isSelected}
                  onChange={() => toggleForwardSelection(forward.id)}
                />
              )}
              <div className="min-w-0">
              <h3 className="font-semibold text-foreground truncate text-sm">{forward.name}</h3>
              <p className="text-xs text-default-500 truncate">{forward.tunnelName}</p>
              </div>
            </div>
            <div className="flex items-center gap-1.5 ml-2">
              {viewMode === 'direct' && !selectionMode && listeners && (
                <div 
                  className={`cursor-grab active:cursor-grabbing p-2 text-default-400 hover:text-default-600 transition-colors touch-manipulation ${
                    isMobile 
                      ? 'opacity-100' // 移动端始终显示
                      : 'opacity-0 group-hover:opacity-100 sm:opacity-0 sm:group-hover:opacity-100'
                  }`}
                  {...listeners}
                  title={isMobile ? "长按拖拽排序" : "拖拽排序"}
                  style={{ touchAction: 'none' }}
                >
                  <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                    <path d="M7 2a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 2zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 8zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 14zm6-8a2 2 0 1 1-.001-4.001A2 2 0 0 1 13 6zm0 2a2 2 0 1 1 .001 4.001A2 2 0 0 1 13 8zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 13 14z" />
                  </svg>
                </div>
              )}
              <Switch
                size="sm"
                isSelected={forward.serviceRunning}
                onValueChange={() => handleServiceToggle(forward)}
                isDisabled={selectionMode || (forward.status !== 1 && forward.status !== 0)}
              />
              <Chip 
                color={statusDisplay.color as any} 
                variant="flat" 
                size="sm"
                className="text-xs"
              >
                {statusDisplay.text}
              </Chip>
            </div>
          </div>
        </CardHeader>
        
        <CardBody className="pt-0 pb-3">
          <div className="space-y-2">
            {/* 地址信息 */}
            <div className="space-y-1">
              <div 
                className={`cursor-pointer px-2 py-1 bg-default-50 dark:bg-default-100/50 rounded border border-default-200 dark:border-default-300 transition-colors duration-200 ${
                  hasMultipleAddresses(forward.inIp) ? 'hover:bg-default-100 dark:hover:bg-default-200/50' : ''
                }`}
                onClick={() => showAddressModal(forward.inIp, forward.inPort, '入口端口')}
                title={formatInAddress(forward.inIp, forward.inPort)}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 min-w-0 flex-1">
                    <span className="text-xs font-medium text-default-600 flex-shrink-0">入口:</span>
                    <code className="text-xs font-mono text-foreground truncate min-w-0">
                      {formatInAddress(forward.inIp, forward.inPort)}
                    </code>
                  </div>
                  {hasMultipleAddresses(forward.inIp) && (
                    <svg className="w-3 h-3 text-default-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                    </svg>
                  )}
                </div>
              </div>
              
              <div 
                className={`cursor-pointer px-2 py-1 bg-default-50 dark:bg-default-100/50 rounded border border-default-200 dark:border-default-300 transition-colors duration-200 ${
                  hasMultipleAddresses(forward.remoteAddr) ? 'hover:bg-default-100 dark:hover:bg-default-200/50' : ''
                }`}
                onClick={() => showAddressModal(forward.remoteAddr, null, '目标地址')}
                title={formatRemoteAddress(forward.remoteAddr)}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 min-w-0 flex-1">
                    <span className="text-xs font-medium text-default-600 flex-shrink-0">目标:</span>
                    <code className="text-xs font-mono text-foreground truncate min-w-0">
                      {formatRemoteAddress(forward.remoteAddr)}
                    </code>
                  </div>
                  {hasMultipleAddresses(forward.remoteAddr) && (
                    <svg className="w-3 h-3 text-default-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                    </svg>
                  )}
                </div>
              </div>
            </div>

            {/* 统计信息 */}
            <div className="flex items-center justify-between pt-2 border-t border-divider">
              <Chip color={strategyDisplay.color as any} variant="flat" size="sm" className="text-xs">
                {strategyDisplay.text}
              </Chip>
              <div className="flex items-center gap-1">
                <Chip variant="flat" size="sm" className="text-xs" color="primary">
                  ↑{formatFlow(forward.inFlow || 0)}
                </Chip>
               
              </div>
              <Chip variant="flat" size="sm" className="text-xs" color="success">
                  ↓{formatFlow(forward.outFlow || 0)}
                </Chip>
            </div>
          </div>
          
          <div className="flex gap-1.5 mt-3">
            <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={() => handleEdit(forward)}
              isDisabled={selectionMode}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z" />
                </svg>
              }
            >
              编辑
            </Button>
            <Button
              size="sm"
              variant="flat"
              color="warning"
              onPress={() => handleDiagnose(forward)}
              isDisabled={selectionMode}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                </svg>
              }
            >
              诊断
            </Button>
            <Button
              size="sm"
              variant="flat"
              color="danger"
              onPress={() => handleDelete(forward)}
              isDisabled={selectionMode}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M9 2a1 1 0 000 2h2a1 1 0 100-2H9z" clipRule="evenodd" />
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8 7a1 1 0 012 0v4a1 1 0 11-2 0V7zM12 7a1 1 0 012 0v4a1 1 0 11-2 0V7z" clipRule="evenodd" />
                </svg>
              }
            >
              删除
            </Button>
          </div>
        </CardBody>
      </Card>
    );
  };

  if (loading) {
    return (
      
        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-3">
            <Spinner size="sm" />
            <span className="text-default-600">正在加载...</span>
          </div>
        </div>
      
    );
  }

  const userGroups = groupForwardsByUserAndTunnel();
  const visibleForwards = viewMode === 'grouped'
    ? userGroups.flatMap(userGroup =>
      userGroup.tunnelGroups.flatMap(tunnelGroup => tunnelGroup.forwards)
    )
    : getSortedForwards();
  const visibleForwardIds = visibleForwards.map(forward => forward.id);
  const allVisibleSelected = visibleForwardIds.length > 0
    && visibleForwardIds.every(id => selectedForwardIds.includes(id));
  const ownUserId = JwtUtil.getUserIdFromToken();
  const isAdmin = JwtUtil.getRoleIdFromToken() === 0;
  const ownRuleGroups = ruleGroups.filter(group => group.userId === ownUserId);
  const filterRuleGroups = isAdmin ? ruleGroups : ownRuleGroups;
  const tableRules = getSortedForwards(true).filter(forward => {
    const matchesGroup = activeGroupId === -1 ||
      (activeGroupId === 0 ? !forward.groupId : forward.groupId === activeGroupId);
    const haystack = `${forward.name} ${forward.tunnelName} ${forward.inIp} ${forward.inPort} ${forward.remoteAddr} ${forward.userName || ""}`.toLowerCase();
    return matchesGroup && haystack.includes(ruleQuery.trim().toLowerCase());
  });
  const tableRuleIds = new Set(tableRules.map(row => row.id));
  const tableSelectedIds = selectedForwardIds.filter(id => tableRuleIds.has(id));
  const selectedOwners = new Set(tableRules.filter(row => tableSelectedIds.includes(row.id)).map(row => row.userId));
  const movableGroups = isAdmin
    ? selectedOwners.size === 1 ? ruleGroups.filter(group => selectedOwners.has(group.userId)) : []
    : ownRuleGroups;
  const pagedRules = tableRules.slice((rulePage - 1) * rulePageSize, rulePage * rulePageSize);

  return (
    
      <div className="px-3 lg:px-6 py-8">
        {/* 页面头部 */}
        <Card className="mb-4 border border-divider shadow-sm">
          <CardBody className="gap-4 p-4 lg:p-6">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h1 className="text-lg font-semibold">转发规则</h1>
              <div className="flex flex-wrap gap-2">
                <Button size="sm" variant="flat" onPress={() => { setSelectedForwardIds([]); setRuleView(ruleView === "table" ? "cards" : "table"); }}>
                  {ruleView === "table" ? "卡片视图" : "表格视图"}
                </Button>
                <Button size="sm" variant="flat" onPress={() => loadData(false)}>刷新</Button>
                <Button size="sm" variant="flat" onPress={() => showForwardStats()}>统计数据</Button>
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <Chip size="sm" variant="flat">规则数：{forwards.length}</Chip>
              <Chip size="sm" variant="flat">已用流量：{formatFlow(forwards.reduce((sum, row) => sum + (row.inFlow || 0) + (row.outFlow || 0), 0))}</Chip>
              <Button size="sm" variant="bordered" onPress={() => setGroupModalOpen(true)}>管理分组</Button>
              {isAdmin && <Button size="sm" variant="bordered" onPress={async () => { setTlsModalOpen(true); const result = await getNodeList(); if (result.code === 0) setTlsNodes(result.data || []); }}>共享 TLS 入口</Button>}
              <Button size="sm" variant={activeGroupId === -1 ? "solid" : "flat"} onPress={() => { setActiveGroupId(-1); setRulePage(1); }}>全部</Button>
              <Button size="sm" variant={activeGroupId === 0 ? "solid" : "flat"} onPress={() => { setActiveGroupId(0); setRulePage(1); }}>未分组</Button>
              {filterRuleGroups.map(group => (
                <Button key={group.id} size="sm" variant={activeGroupId === group.id ? "solid" : "flat"} onPress={() => { setActiveGroupId(group.id); setRulePage(1); }}>
                  {isAdmin ? `${forwards.find(row => row.userId === group.userId)?.userName || `用户 #${group.userId}`} / ` : ""}{group.name}（{group.ruleCount}）
                </Button>
              ))}
            </div>
            <Input
              aria-label="搜索规则"
              placeholder="搜索规则名、隧道、入口、目标或用户"
              value={ruleQuery}
              onChange={event => { setRuleQuery(event.target.value); setRulePage(1); }}
            />
          </CardBody>
        </Card>
        {ruleView === "table" && (
          <div className="mb-4 flex flex-wrap items-center gap-2">
            <Button size="sm" color="primary" onPress={handleAdd}>添加规则</Button>
            <Button size="sm" variant="flat" onPress={handleImport}>批量导入</Button>
            <Button size="sm" variant="flat" onPress={handleExport}>批量导出</Button>
            <Button size="sm" variant="flat" isDisabled={!tableSelectedIds.length} onPress={() => handleBatchMeta("clear")}>清空选中流量</Button>
            <Button size="sm" variant="flat" isDisabled={!tableSelectedIds.length} onPress={() => handleBatchMeta("enable")}>恢复选中</Button>
            <Button size="sm" variant="flat" isDisabled={!tableSelectedIds.length} onPress={() => handleBatchMeta("disable")}>暂停选中</Button>
            <Select aria-label="批量移动到分组" size="sm" className="w-48" items={[{id: 0, name: "未分组"}, ...movableGroups]} selectedKeys={[String(batchGroupId)]} onSelectionChange={keys => {
              const value = Array.from(keys)[0];
              if (value !== undefined) setBatchGroupId(Number(value));
            }}>
              {group => <SelectItem key={String(group.id)}>{group.name}</SelectItem>}
            </Select>
            <Button size="sm" variant="flat" isDisabled={!tableSelectedIds.length} onPress={() => handleBatchMeta("group", batchGroupId)}>移动分组</Button>
            <Select aria-label="批量目标隧道" size="sm" className="w-48" placeholder="目标隧道" selectedKeys={batchTunnelId ? [String(batchTunnelId)] : []} onSelectionChange={keys => {
              const value = Array.from(keys)[0];
              setBatchTunnelId(value === undefined ? null : Number(value));
            }}>
              {tunnels.map(tunnel => <SelectItem key={String(tunnel.id)}>{tunnel.name}</SelectItem>)}
            </Select>
            <Button size="sm" variant="flat" isDisabled={!tableSelectedIds.length || !batchTunnelId} onPress={handleBatchSwitch}>切换入口</Button>
            <Button size="sm" color="danger" variant="flat" isDisabled={!tableSelectedIds.length} onPress={handleBatchDelete}>删除选中</Button>
            {!!tableSelectedIds.length && <span className="text-sm text-default-500">已选择 {tableSelectedIds.length} 条</span>}
          </div>
        )}
        {ruleView === "cards" && <div className="flex items-center justify-between mb-6">
          <div className="flex-1">
          </div>
          <div className="flex items-center gap-3">
            {ruleView === "cards" && (
            <Button
              size="sm"
              variant="flat"
              color="default"
              onPress={handleViewModeChange}
              isIconOnly
              className="text-sm"
              title={viewMode === 'grouped' ? '切换到直接显示' : '切换到分类显示'}
            >
              {viewMode === 'grouped' ? (
                <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2zM3 16a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2z" clipRule="evenodd" />
                </svg>
              ) : (
                <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                  <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
                </svg>
              )}
            </Button>
            )}

            <Button
              size="sm"
              variant="flat"
              color={selectionMode ? 'secondary' : 'default'}
              onPress={toggleSelectionMode}
            >
              {selectionMode ? '退出多选' : '多选'}
            </Button>

            {selectionMode && (
              <>
                <Button
                  size="sm"
                  variant="flat"
                  color="default"
                  onPress={allVisibleSelected ? clearSelectedForwards : selectAllVisibleForwards}
                  isDisabled={visibleForwardIds.length === 0}
                >
                  {allVisibleSelected ? '取消全选' : '全选'}
                </Button>
                <Button
                  size="sm"
                  variant="flat"
                  color="default"
                  onPress={clearSelectedForwards}
                  isDisabled={selectedForwardIds.length === 0}
                >
                  清空选择
                </Button>
                <Button
                  size="sm"
                  variant="flat"
                  color="danger"
                  onPress={handleBatchDelete}
                  isDisabled={selectedForwardIds.length === 0}
                >
                  批量删除（{selectedForwardIds.length}）
                </Button>
              </>
            )}
            
            {/* 导入按钮 */}
            <Button
              size="sm"
              variant="flat"
              color="warning"
              onPress={handleImport}
              isDisabled={selectionMode}
            >
              导入
            </Button>
            
            {/* 导出按钮 */}
            <Button
              size="sm"
              variant="flat"
              color="success"
              onPress={handleExport}
              isLoading={exportLoading}
              isDisabled={selectionMode}
          
            >
              导出
            </Button>

            <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={handleAdd}
              isDisabled={selectionMode}
             
            >
              新增
            </Button>
            
        
          </div>
        </div>}


        {/* 根据显示模式渲染不同内容 */}
        {ruleView === "table" ? (
          <Card className="border border-divider shadow-sm">
            <CardBody className="overflow-x-auto p-0">
              <table className="w-full min-w-[940px] text-sm">
                <thead className="bg-default-50 text-default-600">
                  <tr className="text-left">
                    <th className="p-3"><input type="checkbox" aria-label="全选当前页" checked={pagedRules.length > 0 && pagedRules.every(row => selectedForwardIds.includes(row.id))} onChange={event => {
                      const pageIds = pagedRules.map(row => row.id);
                      setSelectedForwardIds(prev => event.target.checked ? [...new Set([...prev, ...pageIds])] : prev.filter(id => !pageIds.includes(id)));
                    }} /></th>
                    <th className="p-3">规则名</th><th className="p-3">协议</th><th className="p-3">入口</th>
                    <th className="p-3">出口 / 目标</th><th className="p-3">已用流量</th><th className="p-3">状态</th><th className="p-3">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {pagedRules.map(row => (
                    <tr key={row.id} className="border-t border-divider align-top">
                      <td className="p-3"><input type="checkbox" aria-label={`选择${row.name}`} checked={selectedForwardIds.includes(row.id)} onChange={() => toggleForwardSelection(row.id)} /></td>
                      <td className="p-3 font-medium">{row.name} <span className="text-default-400">#{row.id}</span><div className="text-xs text-default-500">{row.userName}</div></td>
                      <td className="p-3">{row.protocol === "tcp" ? "TCP" : row.protocol === "udp" ? "UDP" : "TCP+UDP"}</td>
                      <td className="p-3"><div>{row.tunnelName}</div><button className="text-primary text-left break-all" onClick={() => showAddressModal(row.inIp, row.inPort, "入口地址")}>{formatInAddress(row.inIp, row.inPort)}</button></td>
                      <td className="p-3 break-all">{row.remoteAddr.split(",").join(" / ")}</td>
                      <td className="p-3">{formatFlow(row.trafficBytes ?? ((row.inFlow || 0) + (row.outFlow || 0)))}</td>
                      <td className="p-3"><Chip size="sm" color={getStatusDisplay(row.status).color as any} variant="flat">{getStatusDisplay(row.status).text}</Chip></td>
                      <td className="p-3"><div className="flex flex-wrap gap-1">
                        <Button size="sm" variant="light" onPress={() => setDetailForward(row)}>详情</Button>
                        <Button size="sm" variant="light" onPress={() => handleServiceToggle(row)}>{row.serviceRunning ? "暂停" : "恢复"}</Button>
                        <Button size="sm" variant="light" onPress={() => handleEdit(row)}>编辑</Button>
                        <Button size="sm" variant="light" onPress={() => handleCopy(row)}>复制</Button>
                        <Button size="sm" variant="light" onPress={() => handleRename(row)}>重命名</Button>
                        <Button size="sm" variant="light" onPress={() => handleDiagnose(row)}>诊断</Button>
                        <Button size="sm" variant="light" onPress={() => showForwardStats(row)}>统计</Button>
                        <Button size="sm" variant="light" onPress={() => clearOneForwardTraffic(row)}>清空流量</Button>
                        <Button size="sm" color="danger" variant="light" onPress={() => handleDelete(row)}>删除</Button>
                      </div></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!tableRules.length && <div className="p-10 text-center text-default-500">暂无转发规则，点击「新增」开始。</div>}
              <div className="flex flex-wrap items-center justify-between gap-3 border-t border-divider p-4">
                <span className="text-sm text-default-500">共 {tableRules.length} 条规则</span>
                <div className="flex items-center gap-2">
                  <Select aria-label="每页数量" size="sm" className="w-24" selectedKeys={[String(rulePageSize)]} onSelectionChange={keys => {
                    const value = Number(Array.from(keys)[0]);
                    if (value) { setRulePageSize(value); setRulePage(1); }
                  }}>
                    <SelectItem key="20">20 条</SelectItem><SelectItem key="50">50 条</SelectItem><SelectItem key="100">100 条</SelectItem>
                  </Select>
                  <Button size="sm" isDisabled={rulePage <= 1} onPress={() => setRulePage(page => page - 1)}>上一页</Button>
                  <span className="text-sm">{rulePage} / {Math.max(1, Math.ceil(tableRules.length / rulePageSize))}</span>
                  <Button size="sm" isDisabled={rulePage >= Math.ceil(tableRules.length / rulePageSize)} onPress={() => setRulePage(page => page + 1)}>下一页</Button>
                </div>
              </div>
            </CardBody>
          </Card>
        ) : viewMode === 'grouped' ? (
          /* 按用户和隧道分组的转发列表 */
          userGroups.length > 0 ? (
            <div className="space-y-6">
              {userGroups.map((userGroup) => (
                <Card key={userGroup.userId || 'unknown'} className="shadow-sm border border-divider w-full overflow-hidden">
                  <CardHeader className="pb-3">
                    <div className="flex items-center justify-between w-full min-w-0">
                      <div className="flex items-center gap-3 min-w-0 flex-1">
                        <div className="w-10 h-10 bg-primary-100 dark:bg-primary-900/30 rounded-full flex items-center justify-center flex-shrink-0">
                          <svg className="w-5 h-5 text-primary" fill="currentColor" viewBox="0 0 20 20">
                            <path fillRule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clipRule="evenodd" />
                          </svg>
                        </div>
                        <div className="min-w-0 flex-1">
                          <h2 className="text-base font-medium text-foreground truncate max-w-[150px] sm:max-w-[250px] md:max-w-[350px] lg:max-w-[450px]">{userGroup.userName}</h2>
                          <p className="text-xs text-default-500 truncate max-w-[150px] sm:max-w-[250px] md:max-w-[350px] lg:max-w-[450px]">
                            {userGroup.tunnelGroups.length} 个隧道，
                            {userGroup.tunnelGroups.reduce((total, tg) => total + tg.forwards.length, 0)} 个转发
                          </p>
                        </div>
                      </div>
                      <Chip color="primary" variant="flat" size="sm" className="text-xs flex-shrink-0 ml-2">
                        用户
                      </Chip>
                    </div>
                  </CardHeader>
                  
                  <CardBody className="pt-0">
                    <Accordion variant="splitted" className="px-0">
                      {userGroup.tunnelGroups.map((tunnelGroup) => (
                        <AccordionItem
                          key={tunnelGroup.tunnelId}
                          aria-label={tunnelGroup.tunnelName}
                          title={
                            <div className="flex items-center justify-between w-full min-w-0 pr-4">
                              <div className="flex items-center gap-3 min-w-0 flex-1">
                                <div className="w-8 h-8 bg-success-100 dark:bg-success-900/30 rounded-lg flex items-center justify-center flex-shrink-0">
                                  <svg className="w-4 h-4 text-success" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                                  </svg>
                                </div>
                                <div className="min-w-0 flex-1">
                                  <h3 className="text-sm font-medium text-foreground truncate max-w-[120px] sm:max-w-[200px] md:max-w-[300px] lg:max-w-[400px]">{tunnelGroup.tunnelName}</h3>
                                </div>
                              </div>
                              <div className="flex items-center gap-2 flex-shrink-0 ml-2">
                                <Chip variant="flat" size="sm" className="text-xs">
                                  {tunnelGroup.forwards.filter(f => f.serviceRunning).length}/{tunnelGroup.forwards.length}
                                </Chip>
                              </div>
                            </div>
                          }
                          className="shadow-none border border-divider"
                        >
                          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4 p-4">
                            {tunnelGroup.forwards.map((forward) => renderForwardCard(forward, undefined))}
                          </div>
                        </AccordionItem>
                      ))}
                    </Accordion>
                  </CardBody>
                </Card>
              ))}
            </div>
          ) : (
            /* 空状态 */
            <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
              <CardBody className="text-center py-16">
                <div className="flex flex-col items-center gap-4">
                  <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                    <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-foreground">暂无转发配置</h3>
                    <p className="text-default-500 text-sm mt-1">还没有创建任何转发配置，点击上方按钮开始创建</p>
                  </div>
                </div>
              </CardBody>
            </Card>
          )
        ) : (
          /* 直接显示模式 */
          forwards.length > 0 ? (
            selectionMode ? (
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4">
                {getSortedForwards().map((forward) => (
                  forward && forward.id ? (
                    <div key={forward.id}>{renderForwardCard(forward, undefined)}</div>
                  ) : null
                ))}
              </div>
            ) : (
              <DndContext
                sensors={sensors}
                collisionDetection={closestCenter}
                onDragEnd={handleDragEnd}
                onDragStart={() => {}} // 添加空的 onDragStart 处理器
              >
                <SortableContext
                  items={getSortedForwards().map(f => f.id || 0).filter(id => id > 0)}
                  strategy={rectSortingStrategy}
                >
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4">
                    {getSortedForwards().map((forward) => (
                      forward && forward.id ? (
                        <SortableForwardCard key={forward.id} forward={forward} />
                      ) : null
                    ))}
                  </div>
                </SortableContext>
              </DndContext>
            )
          ) : (
            /* 空状态 */
            <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
              <CardBody className="text-center py-16">
                <div className="flex flex-col items-center gap-4">
                  <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                    <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-foreground">暂无转发配置</h3>
                    <p className="text-default-500 text-sm mt-1">还没有创建任何转发配置，点击上方按钮开始创建</p>
                  </div>
                </div>
              </CardBody>
            </Card>
          )
        )}

        <Modal isOpen={detailForward !== null} onOpenChange={open => { if (!open) setDetailForward(null); }} size="lg">
          <ModalContent>
            <ModalHeader>规则详情</ModalHeader>
            <ModalBody>
              {detailForward && (
                <dl className="grid grid-cols-[6rem_1fr] gap-x-3 gap-y-2 text-sm break-all">
                  <dt className="text-default-500">名称</dt><dd>{detailForward.name} #{detailForward.id}</dd>
                  <dt className="text-default-500">所属用户</dt><dd>{detailForward.userName || `用户 #${detailForward.userId}`}</dd>
                  <dt className="text-default-500">规则分组</dt><dd>{ruleGroups.find(group => group.id === detailForward.groupId)?.name || "未分组"}</dd>
                  <dt className="text-default-500">协议</dt><dd>{detailForward.protocol === "tcp" ? "TCP" : detailForward.protocol === "udp" ? "UDP" : "TCP+UDP"}</dd>
                  <dt className="text-default-500">入口</dt><dd>{detailForward.tunnelName} · {formatInAddress(detailForward.inIp, detailForward.inPort)}</dd>
                  <dt className="text-default-500">目标</dt><dd>{detailForward.remoteAddr.split(",").join(" / ")}</dd>
                  <dt className="text-default-500">状态</dt><dd>{getStatusDisplay(detailForward.status).text}</dd>
                  <dt className="text-default-500">展示流量</dt><dd>{formatFlow(detailForward.trafficBytes ?? ((detailForward.inFlow || 0) + (detailForward.outFlow || 0)))}</dd>
                </dl>
              )}
            </ModalBody>
            <ModalFooter>
              <Button variant="light" onPress={() => setDetailForward(null)}>关闭</Button>
              <Button color="primary" onPress={() => { if (detailForward) showForwardStats(detailForward); setDetailForward(null); }}>查看统计</Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        <Modal isOpen={statsOpen} onOpenChange={setStatsOpen} size="xl">
          <ModalContent>
            <ModalHeader>{statsTitle}</ModalHeader>
            <ModalBody>
              <p className="text-sm">合计 {formatFlow(ruleHours.reduce((sum, row) => sum + Number(row.bytes || 0), 0))}</p>
              <div className="flex h-48 items-end gap-1 border-b border-divider pb-2">
                {Array.from({ length: 24 }, (_, index) => {
                  const hour = (Math.floor(Date.now() / 3600000) - 23 + index) * 3600000;
                  const bytes = Number(ruleHours.find(row => Number(row.hourStart) === hour)?.bytes || 0);
                  const peak = Math.max(1, ...ruleHours.map(row => Number(row.bytes || 0)));
                  return <div key={hour} className="flex-1 min-w-1 rounded-t bg-primary" style={{ height: `${Math.max(2, bytes / peak * 100)}%` }} title={`${new Date(hour).toLocaleString()} · ${formatFlow(bytes)}`} />;
                })}
              </div>
              <p className="text-xs text-default-500">按小时汇总已上报的转发流量；清空展示流量不会删除历史记录。</p>
            </ModalBody>
          </ModalContent>
        </Modal>

        <Modal isOpen={tlsModalOpen} onOpenChange={setTlsModalOpen} size="lg" scrollBehavior="inside">
          <ModalContent><ModalHeader>共享 TLS 入口</ModalHeader><ModalBody>
            <p className="text-sm text-default-500">多个 TCP 规则共用端口，按客户端 SNI 分流，原样透传 TLS。无需在节点安装证书。</p>
            {tlsInbounds.map(item => <div key={item.id} className="flex items-center justify-between gap-2 border-b py-2">
              <span>{item.enabled === 0 ? "已关闭 · " : ""}{item.name} · 隧道 #{item.tunnelId} · :{item.port}<br />{item.allowedSni || "不限域名"}</span>
              <Button size="sm" variant="light" onPress={async () => {
                const result = await toggleTlsInbound({ id: item.id, enabled: item.enabled === 0 ? 1 : 0 });
                if (result.code === 0) { toast.success("入口状态已更新"); loadData(false); }
                else toast.error(result.msg || "切换失败");
              }}>{item.enabled === 0 ? "开启" : "关闭"}</Button>
              <Button size="sm" variant="light" onPress={() => {
                const p = JSON.parse(item.policy || "{}");
                setTlsPolicyId(item.id);
                setTlsPolicy({ proxyAccept: p.sniProxy || 0, trustedCidrs: (p.sniTrustedCIDRs || []).join(","), defaultRuleId: String(p.defaultRuleId || 0),
                  rejectEmpty: p.sniRejectEmpty !== false, unknownDefault: !!p.sniUnknownDefault, maxHandshakes: String(p.sniMaxHandshakes || 128), timeoutSeconds: String(p.sniTimeout || "5s").replace(/s$/, "") });
              }}>策略</Button>
              <Button size="sm" variant="light" onPress={() => setTlsDraft({ id: item.id, name: item.name, tunnelId: String(item.tunnelId), port: String(item.port), allowedSni: item.allowedSni })}>编辑</Button>
              <Button size="sm" color="danger" variant="light" onPress={async () => {
                const result = await deleteTlsInbound({ id: item.id });
                if (result.code === 0) { toast.success("入口已删除"); loadData(false); }
                else toast.error(result.msg || "删除失败");
              }}>删除</Button>
            </div>)}
            {tlsPolicyId > 0 && <div className="space-y-3 rounded border p-3">
              <p>入口 #{tlsPolicyId} 策略（保存时会短暂重启其活跃规则）</p>
              <Switch isSelected={tlsPolicy.proxyAccept === 1} onValueChange={value => setTlsPolicy(p => ({ ...p, proxyAccept: value ? 1 : 0 }))}>接收 Proxy v1/v2</Switch>
              <Textarea label="可信 Proxy 来源 CIDR（逗号分隔）" value={tlsPolicy.trustedCidrs} onValueChange={trustedCidrs => setTlsPolicy(p => ({ ...p, trustedCidrs }))} />
              <Input label="管理员默认规则 ID（0 不设置）" value={tlsPolicy.defaultRuleId} onValueChange={defaultRuleId => setTlsPolicy(p => ({ ...p, defaultRuleId }))} />
              <Switch isSelected={tlsPolicy.rejectEmpty} onValueChange={rejectEmpty => setTlsPolicy(p => ({ ...p, rejectEmpty }))}>拒绝无 SNI</Switch>
              <Switch isSelected={tlsPolicy.unknownDefault} onValueChange={unknownDefault => setTlsPolicy(p => ({ ...p, unknownDefault }))}>未知 SNI 使用默认规则</Switch>
              <Input label="ClientHello 并发上限" value={tlsPolicy.maxHandshakes} onValueChange={maxHandshakes => setTlsPolicy(p => ({ ...p, maxHandshakes }))} />
              <Input label="读取超时（秒）" value={tlsPolicy.timeoutSeconds} onValueChange={timeoutSeconds => setTlsPolicy(p => ({ ...p, timeoutSeconds }))} />
              <Button onPress={async () => {
                const result = await saveTlsPolicy({ ...tlsPolicy, id: tlsPolicyId, defaultRuleId: Number(tlsPolicy.defaultRuleId), maxHandshakes: Number(tlsPolicy.maxHandshakes), timeoutSeconds: Number(tlsPolicy.timeoutSeconds) });
                if (result.code === 0) { toast.success("策略已保存"); setTlsPolicyId(0); loadData(false); } else toast.error(result.msg || "保存失败");
              }}>保存入口策略</Button>
            </div>}
            <div className="space-y-2 rounded border p-3">
              <Select label="节点" selectedKeys={tlsNodeId ? [tlsNodeId] : []} onSelectionChange={keys => setTlsNodeId(String(Array.from(keys)[0] || ""))}>
                {tlsNodes.map(node => <SelectItem key={String(node.id)}>{node.name}{node.tlsOnly ? "（仅 TLS）" : ""}</SelectItem>)}
              </Select>
              <p className="text-xs text-default-500">仅 TLS 模式禁止该节点新增或恢复普通 TCP 规则，UDP 不受影响。开启前须迁移现有普通 TCP 规则。</p>
              {[1, 0].map(only => <Button key={only} size="sm" onPress={async () => {
                const result = await saveNodeTlsPolicy({ nodeId: Number(tlsNodeId), tlsOnly: only });
                if (result.code === 0) toast.success("节点策略已保存"); else toast.error(result.msg || "保存失败");
              }}>{only ? "开启仅 TLS" : "关闭仅 TLS"}</Button>)}
            </div>
            <Input label="入口名称" value={tlsDraft.name} onValueChange={name => setTlsDraft(prev => ({ ...prev, name }))} />
            <Select isDisabled={!!tlsDraft.id} label="所属隧道" selectedKeys={tlsDraft.tunnelId ? [tlsDraft.tunnelId] : []}
              onSelectionChange={keys => setTlsDraft(prev => ({ ...prev, tunnelId: String(Array.from(keys)[0] || "") }))}>
              {tunnels.map(tunnel => <SelectItem key={String(tunnel.id)}>{tunnel.name}</SelectItem>)}
            </Select>
            <Input isDisabled={!!tlsDraft.id} label="共享端口" type="number" value={tlsDraft.port} onValueChange={port => setTlsDraft(prev => ({ ...prev, port }))} />
            <Textarea label="允许的 SNI 范围（留空不限）" placeholder={"example.com\n.example.org"} value={tlsDraft.allowedSni}
              onValueChange={allowedSni => setTlsDraft(prev => ({ ...prev, allowedSni }))} />
          </ModalBody><ModalFooter><Button onPress={() => setTlsModalOpen(false)}>关闭</Button>
            <Button color="primary" onPress={async () => {
              const result = await (tlsDraft.id ? updateTlsInbound : createTlsInbound)({ ...tlsDraft, tunnelId: Number(tlsDraft.tunnelId), port: Number(tlsDraft.port) });
              if (result.code === 0) { toast.success("入口已保存"); setTlsDraft({ id: 0, name: "", tunnelId: "", port: "443", allowedSni: "" }); loadData(false); }
              else toast.error(result.msg || "创建失败");
            }}>{tlsDraft.id ? "保存修改" : "创建入口"}</Button>
          </ModalFooter></ModalContent>
        </Modal>
        <Modal isOpen={groupModalOpen} onOpenChange={setGroupModalOpen} size="md">
          <ModalContent>
            <ModalHeader>管理规则分组</ModalHeader>
            <ModalBody>
              <div className="flex gap-2">
                <Input
                  label="分组名称"
                  value={groupName}
                  onChange={(event) => setGroupName(event.target.value)}
                  maxLength={64}
                />
                <Button color="primary" onPress={handleCreateGroup}>添加</Button>
              </div>
              <div className="space-y-2">
                {ruleGroups.filter(group => group.userId === JwtUtil.getUserIdFromToken()).map(group => (
                  <div key={group.id} className="flex items-center justify-between border border-divider rounded-lg p-2">
                    <span>{group.name}（{group.ruleCount} 条）</span>
                    <div className="flex gap-1">
                    <Button size="sm" variant="light" onPress={async () => {
                      const name = window.prompt("分组新名称", group.name)?.trim();
                      if (name === undefined) return;
                      if (!name || name.length > 64) return toast.error("分组名称须为 1–64 个字符");
                      const result = await updateForwardGroup(group.id, name);
                      if (result.code !== 0) return toast.error(result.msg || "分组改名失败");
                      await loadData(false);
                    }}>改名</Button>
                    <Button size="sm" color="danger" variant="light" onPress={async () => {
                      if (!window.confirm("删除分组后，其中规则会移至未分组。")) return;
                      const result = await deleteForwardGroup(group.id);
                      if (result.code !== 0) return toast.error(result.msg || "删除分组失败");
                      await loadData(false);
                    }}>删除</Button>
                    </div>
                  </div>
                ))}
              </div>
            </ModalBody>
          </ModalContent>
        </Modal>

        {/* 新增/编辑模态框 */}
        <Modal 
          isOpen={modalOpen}
          onOpenChange={setModalOpen}
          size="2xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">
                    {isEdit ? '编辑转发' : '新增转发'}
                  </h2>
                  <p className="text-small text-default-500">
                    {isEdit ? '修改现有转发配置的信息' : '创建新的转发配置'}
                  </p>
                </ModalHeader>
                <ModalBody>
                  <div className="space-y-4 pb-4">
                    <Select
                      label="转发协议"
                      selectedKeys={[form.protocol]}
                      onSelectionChange={(keys) => {
                        const protocol = Array.from(keys)[0] as ForwardForm["protocol"];
                        if (protocol) setForm(prev => protocol === "tcp" ? { ...prev, protocol }
                          : { ...prev, protocol, tlsInboundId: 0, sniNames: "", originTls: 0, originServerName: "", originRootCa: "", originAlpn: "", proxyAccept: 0, proxySend: 0, proxyTrustedCidrs: "" });
                      }}
                      variant="bordered"
                      description="可选 TCP、UDP；旧规则保持 TCP+UDP，编辑时也可切换"
                    >
                      <SelectItem key="tcp">TCP</SelectItem>
                      <SelectItem key="udp">UDP</SelectItem>
                      <SelectItem key="tcp_udp">TCP+UDP（兼容模式）</SelectItem>
                    </Select>
                    {form.protocol === "tcp" && (
                      <div className="space-y-3 rounded-lg border border-default-200 p-3">
                        <Select label="入站方式" items={[{ id: 0, name: "独立端口", port: 0 }, ...tlsInbounds.filter(item => item.tunnelId === Number(form.tunnelId))]} selectedKeys={[String(form.tlsInboundId)]}
                          onSelectionChange={keys => { const id = Number(Array.from(keys)[0]);
                            if (Number.isFinite(id)) setForm(prev => ({ ...prev, tlsInboundId: id, sniNames: "",
                              ...(id ? { originTls: 0, proxyAccept: 0 } : {}) })); }}>
                          {item => <SelectItem key={String(item.id)}>{item.id ? `${item.name} :${item.port}` : item.name}</SelectItem>}
                        </Select>
                        {!!form.tlsInboundId && <Textarea label="SNI 域名（每行一个）"
                          value={form.sniNames.split(",").join("\n")}
                          onValueChange={value => setForm(prev => ({ ...prev, sniNames: value.replace(/[\r\n]+/g, ",") }))}
                          description="客户端 TLS 域名必须匹配；目标需提供对应域名的证书。未知域名拒绝连接。" />}
                        <Select isDisabled={!!form.tlsInboundId} label="回源 TLS" selectedKeys={[String(form.originTls)]}
                          onSelectionChange={keys => { const value = Array.from(keys)[0];
                            if (value !== undefined) setForm(prev => ({ ...prev, originTls: Number(value) })); }}>
                          <SelectItem key="0">关闭</SelectItem>
                          <SelectItem key="1">开启（验证目标证书）</SelectItem>
                        </Select>
                        {form.originTls === 1 && <>
                          <Input label="TLS 校验名称 / SNI" placeholder="留空使用目标主机名"
                            value={form.originServerName} onValueChange={value => setForm(prev => ({ ...prev, originServerName: value }))} />
                          <Textarea label="自定义根证书（PEM）" placeholder="留空使用系统根证书"
                            value={form.originRootCa} onValueChange={value => setForm(prev => ({ ...prev, originRootCa: value }))} />
                          <Input label="ALPN（可选，逗号分隔）" placeholder="h2,http/1.1"
                            value={form.originAlpn} onValueChange={value => setForm(prev => ({ ...prev, originAlpn: value }))} />
                          <p className="text-xs text-default-500">仅加密至目标的连接；目标服务必须支持 TLS。发送 Proxy 头时，头部也在 TLS 内传输。</p>
                        </>}
                        <p className="text-sm font-medium">Proxy Protocol</p>
                        <p className="text-xs text-default-500">入口节点需升级到支持版本。接收模式只信任下方填写的来源网段。</p>
                        <Select isDisabled={!!form.tlsInboundId} label="接收" selectedKeys={[String(form.proxyAccept)]}
                          onSelectionChange={keys => {
                            const value = Array.from(keys)[0];
                            if (value !== undefined) setForm(prev => ({ ...prev, proxyAccept: Number(value) }));
                          }}>
                          <SelectItem key="0">关闭</SelectItem>
                          <SelectItem key="1">自动识别 v1/v2</SelectItem>
                        </Select>
                        {form.proxyAccept > 0 && <Textarea label="可信来源 CIDR"
                          placeholder={"192.0.2.0/24\n2001:db8::/32"}
                          value={form.proxyTrustedCidrs.split(",").join("\n")}
                          onChange={event => setForm(prev => ({ ...prev, proxyTrustedCidrs: event.target.value.replace(/[\r\n]+/g, ",") }))}
                          description="每行一个 IP 网段。请填写实际发送 Proxy 头的上游地址。" />}
                        <Select label="发送" selectedKeys={[String(form.proxySend)]}
                          onSelectionChange={keys => {
                            const value = Array.from(keys)[0];
                            if (value !== undefined) setForm(prev => ({ ...prev, proxySend: Number(value) }));
                          }}>
                          <SelectItem key="0">关闭</SelectItem>
                          <SelectItem key="1">v1</SelectItem>
                          <SelectItem key="2">v2</SelectItem>
                        </Select>
                      </div>
                    )}
                    <Input
                      label="转发名称"
                      placeholder="请输入转发名称"
                      value={form.name}
                      onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                      isInvalid={!!errors.name}
                      errorMessage={errors.name}
                      variant="bordered"
                    />
                    
                    <Select
                      label="选择隧道"
                      placeholder="请选择关联的隧道"
                      selectedKeys={form.tunnelId ? [form.tunnelId.toString()] : []}
                      onSelectionChange={(keys) => {
                        const selectedKey = Array.from(keys)[0] as string;
                        if (selectedKey) {
                          handleTunnelChange(selectedKey);
                        }
                      }}
                      isInvalid={!!errors.tunnelId}
                      errorMessage={errors.tunnelId}
                      variant="bordered"
                    >
                      {tunnels.map((tunnel) => (
                        <SelectItem key={tunnel.id} >
                          {tunnel.name}
                        </SelectItem>
                      ))}
                    </Select>

                    <Select
                      label="规则分组"
                      items={[
                        { id: 0, name: "未分组" },
                        ...ruleGroups.filter(group => group.userId === (form.userId || JwtUtil.getUserIdFromToken()))
                      ]}
                      selectedKeys={[String(form.groupId)]}
                      onSelectionChange={(keys) => {
                        const value = Array.from(keys)[0];
                        if (value !== undefined) setForm(prev => ({ ...prev, groupId: Number(value) }));
                      }}
                      variant="bordered"
                    >
                      {(group) => (
                        <SelectItem key={String(group.id)}>{group.name}</SelectItem>
                      )}
                    </Select>

                    <Input
                      isDisabled={!!form.tlsInboundId}
                      label="入口端口"
                      placeholder="留空则自动分配可用端口"
                      type="number"
                      value={form.inPort !== null ? form.inPort.toString() : ''}
                      onChange={(e) => {
                        const value = e.target.value;
                        setForm(prev => ({ 
                          ...prev, 
                          inPort: value ? parseInt(value) : null
                        }));
                      }}
                      isInvalid={!!errors.inPort}
                      errorMessage={errors.inPort}
                      variant="bordered"
                      description={form.tlsInboundId ? "使用共享入口的固定端口，此处填写值不生效" : "指定入口端口，留空则从节点可用端口中自动分配"}
                    />
                    
                    <Textarea
                      label="远程地址"
                      placeholder="请输入远程地址，多个地址用换行分隔&#10;例如:&#10;192.168.1.100:8080&#10;example.com:3000"
                      value={form.remoteAddr}
                      onChange={(e) => setForm(prev => ({ ...prev, remoteAddr: e.target.value }))}
                      isInvalid={!!errors.remoteAddr}
                      errorMessage={errors.remoteAddr}
                      variant="bordered"
                      description="格式: IP:端口 或 域名:端口，支持多个地址（每行一个）"
                      minRows={3}
                      maxRows={6}
                    />
                    
                    {getAddressCount(form.remoteAddr) > 1 && (
                      <Select
                        label="负载策略"
                        placeholder="请选择负载均衡策略"
                        selectedKeys={[form.strategy]}
                        onSelectionChange={(keys) => {
                          const selectedKey = Array.from(keys)[0] as string;
                          setForm(prev => ({ ...prev, strategy: selectedKey }));
                        }}
                        variant="bordered"
                        description="多个目标地址的负载均衡策略"
                      >
                        <SelectItem key="fifo" >主备模式 - 自上而下</SelectItem>
                        <SelectItem key="round" >轮询模式 - 依次轮换</SelectItem>
                        <SelectItem key="rand" >随机模式 - 随机选择</SelectItem>
                        <SelectItem key="hash" >哈希模式 - IP哈希</SelectItem>
                      </Select>
                    )}
                  </div>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button 
                    color="primary" 
                    onPress={handleSubmit}
                    isLoading={submitLoading}
                  >
                    {isEdit ? '保存修改' : '创建转发'}
                  </Button>
                </ModalFooter>
              </>
            )}
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
                  <h2 className="text-lg font-bold text-danger">确认删除</h2>
                </ModalHeader>
                <ModalBody>
                  <p className="text-default-600">
                    确定要删除转发 <span className="font-semibold text-foreground">"{forwardToDelete?.name}"</span> 吗？
                  </p>
                  <p className="text-small text-default-500 mt-2">
                    此操作无法撤销，删除后该转发将永久消失。
                  </p>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button 
                    color="danger" 
                    onPress={confirmDelete}
                    isLoading={deleteLoading}
                  >
                    确认删除
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 批量删除确认模态框 */}
        <Modal
          isOpen={batchDeleteModalOpen}
          onOpenChange={setBatchDeleteModalOpen}
          size="2xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-lg font-bold text-danger">确认批量删除</h2>
                </ModalHeader>
                <ModalBody>
                  <p className="text-default-600">
                    确定要删除已选择的 <span className="font-semibold text-foreground">{batchDeleteIds.length}</span> 条转发吗？
                  </p>
                  <p className="text-small text-default-500 mt-2">
                    系统会逐条执行常规删除；失败的规则会保留。此操作无法撤销。
                  </p>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button
                    color="danger"
                    onPress={confirmBatchDelete}
                    isLoading={batchDeleteLoading}
                  >
                    确认批量删除
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 地址列表弹窗 */}
        <Modal isOpen={addressModalOpen} onClose={() => setAddressModalOpen(false)} size="lg" scrollBehavior="outside">
          <ModalContent>
            <ModalHeader className="text-base">{addressModalTitle}</ModalHeader>
            <ModalBody className="pb-6">
              <div className="mb-4 text-right">
                <Button size="sm" onClick={copyAllAddresses}>
                  复制
                </Button>
              </div>
              
              <div className="space-y-2 max-h-60 overflow-y-auto">
                {addressList.map((item) => (
                  <div key={item.id} className="flex justify-between items-center p-3 border border-default-200 dark:border-default-100 rounded-lg">
                    <code className="text-sm flex-1 mr-3 text-foreground">{item.address}</code>
                    <Button
                      size="sm"
                      variant="light"
                      isLoading={item.copying}
                      onClick={() => copyAddress(item)}
                    >
                      复制
                    </Button>
                  </div>
                ))}
              </div>
            </ModalBody>
          </ModalContent>
        </Modal>

        {/* 导出数据模态框 */}
        <Modal 
          isOpen={exportModalOpen} 
          onClose={() => {
            setExportModalOpen(false);
            setSelectedTunnelForExport(null);
            setExportData('');
          }} 
          
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <h2 className="text-xl font-bold">导出转发数据</h2>
              <p className="text-small text-default-500">
                完整 JSON 保留协议、分组及 Proxy 设置；nyanpass 适用于 TCP 规则。
              </p>
            </ModalHeader>
            <ModalBody className="pb-6">
              <div className="space-y-4">
                {/* 隧道选择 */}
                <div>
                  <Select
                    label="选择导出隧道"
                    placeholder="请选择要导出的隧道"
                    selectedKeys={selectedTunnelForExport ? [selectedTunnelForExport.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selectedKey = Array.from(keys)[0] as string;
                      setSelectedTunnelForExport(selectedKey ? parseInt(selectedKey) : null);
                    }}
                    variant="bordered"
                    isRequired
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>
                  <Select label="导出格式" className="mt-3" selectedKeys={[exportFormat]} onSelectionChange={keys => {
                    const value = Array.from(keys)[0] as typeof exportFormat;
                    if (value) { setExportFormat(value); setExportData(""); }
                  }}>
                    <SelectItem key="json">Flux 完整 JSON</SelectItem>
                    <SelectItem key="nyanpass">nyanpass JSONL</SelectItem>
                    <SelectItem key="legacy">旧版文本：目标|名称|端口</SelectItem>
                  </Select>
                </div>

                {/* 导出按钮和数据 */}
                {exportData && (
                  <div className="flex justify-between items-center">
                    <Button 
                      color="primary" 
                      size="sm" 
                      onPress={executeExport}
                      isLoading={exportLoading}
                      isDisabled={!selectedTunnelForExport}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
                        </svg>
                      }
                    >
                      重新生成
                    </Button>
                    <Button 
                      color="secondary" 
                      size="sm" 
                      onPress={copyExportData}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path d="M8 3a1 1 0 011-1h2a1 1 0 110 2H9a1 1 0 01-1-1z" />
                          <path d="M6 3a2 2 0 00-2 2v11a2 2 0 002 2h8a2 2 0 002-2V5a2 2 0 00-2-2 3 3 0 01-3 3H9a3 3 0 01-3-3z" />
                        </svg>
                      }
                    >
                      复制
                    </Button>
                  </div>
                )}

                {/* 初始导出按钮 */}
                {!exportData && (
                  <div className="text-right">
                    <Button 
                      color="primary" 
                      size="sm" 
                      onPress={executeExport}
                      isLoading={exportLoading}
                      isDisabled={!selectedTunnelForExport}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
                        </svg>
                      }
                    >
                      生成导出数据
                    </Button>
                  </div>
                )}

                {/* 导出数据显示 */}
                {exportData && (
                  <div className="relative">
                    <Textarea
                      id="forward-export-data"
                      ref={exportTextareaRef}
                      value={exportData}
                      readOnly
                      variant="bordered"
                      minRows={10}
                      maxRows={20}
                      className="font-mono text-sm"
                      classNames={{
                        input: "font-mono text-sm"
                      }}
                      placeholder="暂无数据"
                    />
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button 
                variant="light" 
                onPress={() => setExportModalOpen(false)}
              >
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 导入数据模态框 */}
        <Modal 
          isOpen={importModalOpen} 
          onClose={() => setImportModalOpen(false)} 
          
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <h2 className="text-xl font-bold">导入转发数据</h2>
              <p className="text-small text-default-500">
                支持 Flux JSON、Nekopass 普通规则 JSON、nyanpass JSONL，以及旧版目标|名称|端口格式。
              </p>
              <p className="text-small text-default-400">
                目标地址支持单个地址(如：example.com:8080)或多个地址用逗号分隔(如：3.3.3.3:3,4.4.4.4:4)
              </p>
            </ModalHeader>
            <ModalBody className="pb-6">
              <div className="space-y-4">
                {/* 隧道选择 */}
                <div>
                  <Select
                    label="选择导入隧道"
                    placeholder="请选择要导入的隧道"
                    selectedKeys={selectedTunnelForImport ? [selectedTunnelForImport.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selectedKey = Array.from(keys)[0] as string;
                      setSelectedTunnelForImport(selectedKey ? parseInt(selectedKey) : null);
                    }}
                    variant="bordered"
                    isRequired
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>
                </div>

                {/* 输入区域 */}
                <div>
                  <Textarea
                    label="导入数据"
                    placeholder="请输入要导入的转发数据，格式：目标地址|转发名称|入口端口"
                    value={importData}
                    onChange={(e) => setImportData(e.target.value)}
                    variant="flat"
                    minRows={8}
                    maxRows={12}
                    classNames={{
                      input: "font-mono text-sm"
                    }}
                  />

                
                </div>

                {/* 导入结果 */}
                {importResults.length > 0 && (
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <h3 className="text-base font-semibold">导入结果</h3>
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-default-500">
                          成功：{importResults.filter(r => r.success).length} / 
                          总计：{importResults.length}
                        </span>
                      </div>
                    </div>
                    
                    <div className="max-h-40 overflow-y-auto space-y-1" style={{
                      scrollbarWidth: 'thin',
                      scrollbarColor: 'rgb(156 163 175) transparent'
                    }}>
                      {importResults.map((result, index) => (
                        <div 
                          key={index} 
                          className={`p-2 rounded border ${
                            result.success 
                              ? 'bg-success-50 dark:bg-success-100/10 border-success-200 dark:border-success-300/20' 
                              : 'bg-danger-50 dark:bg-danger-100/10 border-danger-200 dark:border-danger-300/20'
                          }`}
                        >
                          <div className="flex items-center gap-2">
                            {result.success ? (
                              <svg className="w-3 h-3 text-success-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                              </svg>
                            ) : (
                              <svg className="w-3 h-3 text-danger-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
                              </svg>
                            )}
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 mb-0.5">
                                <span className={`text-xs font-medium ${
                                  result.success ? 'text-success-700 dark:text-success-300' : 'text-danger-700 dark:text-danger-300'
                                }`}>
                                  {result.success ? '成功' : '失败'}
                                </span>
                                <span className="text-xs text-default-500">|</span>
                                <code className="text-xs font-mono text-default-600 truncate">{result.line}</code>
                              </div>
                              <div className={`text-xs ${
                                result.success ? 'text-success-600 dark:text-success-400' : 'text-danger-600 dark:text-danger-400'
                              }`}>
                                {result.message}
                              </div>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button 
                variant="light" 
                onPress={() => setImportModalOpen(false)}
              >
                关闭
              </Button>
              <Button 
                color="warning" 
                onPress={executeImport}
                isLoading={importLoading}
                isDisabled={!importData.trim() || !selectedTunnelForImport}
              >
                开始导入
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 诊断结果模态框 */}
        <Modal 
          isOpen={diagnosisModalOpen}
          onOpenChange={setDiagnosisModalOpen}
          size="4xl"
          scrollBehavior="inside"
          backdrop="blur"
          placement="center"
          classNames={{
            base: "rounded-2xl",
            header: "rounded-t-2xl",
            body: "rounded-none",
            footer: "rounded-b-2xl"
          }}
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1 bg-content1 border-b border-divider">
                  <h2 className="text-xl font-bold">转发诊断结果</h2>
                  {currentDiagnosisForward && (
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-small text-default-500 truncate flex-1 min-w-0">{currentDiagnosisForward.name}</span>
                      <Chip 
                        color="primary"
                        variant="flat" 
                        size="sm"
                        className="flex-shrink-0"
                      >
                        转发服务
                      </Chip>
                    </div>
                  )}
                </ModalHeader>
                <ModalBody className="bg-content1">
                  {diagnosisLoading ? (
                    <div className="flex items-center justify-center py-16">
                      <div className="flex items-center gap-3">
                        <Spinner size="sm" />
                        <span className="text-default-600">正在诊断...</span>
                      </div>
                    </div>
                  ) : diagnosisResult ? (
                    <div className="space-y-4">
                      {/* 统计摘要 */}
                      <div className="grid grid-cols-3 gap-3">
                        <div className="text-center p-3 bg-default-100 dark:bg-gray-800 rounded-lg border border-divider">
                          <div className="text-2xl font-bold text-foreground">{diagnosisResult.results.length}</div>
                          <div className="text-xs text-default-500 mt-1">总测试数</div>
                        </div>
                        <div className="text-center p-3 bg-success-50 dark:bg-success-900/20 rounded-lg border border-success-200 dark:border-success-700">
                          <div className="text-2xl font-bold text-success-600 dark:text-success-400">
                            {diagnosisResult.results.filter(r => r.success).length}
                          </div>
                          <div className="text-xs text-success-600 dark:text-success-400/80 mt-1">成功</div>
                        </div>
                        <div className="text-center p-3 bg-danger-50 dark:bg-danger-900/20 rounded-lg border border-danger-200 dark:border-danger-700">
                          <div className="text-2xl font-bold text-danger-600 dark:text-danger-400">
                            {diagnosisResult.results.filter(r => !r.success).length}
                          </div>
                          <div className="text-xs text-danger-600 dark:text-danger-400/80 mt-1">失败</div>
                        </div>
                      </div>

                      {/* 桌面端表格展示 */}
                      <div className="hidden md:block space-y-3">
                        {(() => {
                          // 使用后端返回的 chainType 和 inx 字段进行分组
                          const groupedResults = {
                            entry: diagnosisResult.results.filter(r => r.fromChainType === 1),
                            chains: {} as Record<number, typeof diagnosisResult.results>,
                            exit: diagnosisResult.results.filter(r => r.fromChainType === 3)
                          };
                          
                          // 按 inx 分组链路测试
                          diagnosisResult.results.forEach(r => {
                            if (r.fromChainType === 2 && r.fromInx != null) {
                              if (!groupedResults.chains[r.fromInx]) {
                                groupedResults.chains[r.fromInx] = [];
                              }
                              groupedResults.chains[r.fromInx].push(r);
                            }
                          });

                          const renderTableSection = (title: string, results: typeof diagnosisResult.results) => {
                            if (results.length === 0) return null;
                            
                            return (
                              <div key={title} className="border border-divider rounded-lg overflow-hidden bg-white dark:bg-gray-800">
                                <div className="bg-primary/10 dark:bg-primary/20 px-3 py-2 border-b border-divider">
                                  <h3 className="text-sm font-semibold text-primary">{title}</h3>
                                </div>
                                <table className="w-full text-sm">
                                  <thead className="bg-default-100 dark:bg-gray-700">
                                    <tr>
                                      <th className="px-3 py-2 text-left font-semibold text-xs">路径</th>
                                      <th className="px-3 py-2 text-center font-semibold text-xs w-20">状态</th>
                                      <th className="px-3 py-2 text-center font-semibold text-xs w-24">延迟(ms)</th>
                                      <th className="px-3 py-2 text-center font-semibold text-xs w-24">丢包率</th>
                                      <th className="px-3 py-2 text-center font-semibold text-xs w-20">质量</th>
                                    </tr>
                                  </thead>
                                  <tbody className="divide-y divide-divider bg-white dark:bg-gray-800">
                                    {results.map((result, index) => {
                              const quality = getQualityDisplay(result.averageTime, result.packetLoss);
                              
                              return (
                                <tr key={index} className={`hover:bg-default-50 dark:hover:bg-gray-700/50 ${
                                  result.success ? 'bg-white dark:bg-gray-800' : 'bg-danger-50 dark:bg-danger-900/30'
                                }`}>
                                  <td className="px-3 py-2">
                                    <div className="flex items-center gap-2">
                                      <span className={`w-5 h-5 rounded-full flex items-center justify-center text-xs ${
                                        result.success 
                                          ? 'bg-success text-white' 
                                          : 'bg-danger text-white'
                                      }`}>
                                        {result.success ? '✓' : '✗'}
                                      </span>
                                      <div className="flex-1 min-w-0">
                                        <div className="font-medium text-foreground truncate">{result.description}</div>
                                        <div className="text-xs text-default-500 truncate">
                                          {result.targetIp}:{result.targetPort}
                                        </div>
                                      </div>
                                    </div>
                                  </td>
                                  <td className="px-3 py-2 text-center">
                                    <Chip 
                                      color={result.success ? 'success' : 'danger'} 
                                      variant="flat"
                                      size="sm"
                                      className="min-w-[50px]"
                                    >
                                      {result.success ? '成功' : '失败'}
                                    </Chip>
                                  </td>
                                  <td className="px-3 py-2 text-center">
                                    {result.success ? (
                                      <span className="font-semibold text-primary">
                                        {result.averageTime?.toFixed(0)}
                                      </span>
                                    ) : (
                                      <span className="text-default-400">-</span>
                                    )}
                                  </td>
                                  <td className="px-3 py-2 text-center">
                                    {result.success ? (
                                      <span className={`font-semibold ${
                                        (result.packetLoss || 0) > 0 ? 'text-warning' : 'text-success'
                                      }`}>
                                        {result.packetLoss?.toFixed(1)}%
                                      </span>
                                    ) : (
                                      <span className="text-default-400">-</span>
                                    )}
                                  </td>
                                  <td className="px-3 py-2 text-center">
                                    {result.success && quality ? (
                                      <Chip 
                                        color={quality.color as any} 
                                        variant="flat" 
                                        size="sm"
                                        className="text-xs"
                                      >
                                        {quality.text}
                                      </Chip>
                                    ) : (
                                      <span className="text-default-400">-</span>
                                    )}
                                  </td>
                                </tr>
                              );
                                    })}
                                  </tbody>
                                </table>
                              </div>
                            );
                          };

                          return (
                            <>
                              {/* 入口测试 */}
                              {renderTableSection('🚪 入口测试', groupedResults.entry)}
                              
                              {/* 链路测试（按跳数排序） */}
                              {Object.keys(groupedResults.chains)
                                .map(Number)
                                .sort((a, b) => a - b)
                                .map(hop => renderTableSection(`🔗 转发链 - 第${hop}跳`, groupedResults.chains[hop]))}
                              
                              {/* 出口测试 */}
                              {renderTableSection('🚀 出口测试', groupedResults.exit)}
                            </>
                          );
                        })()}
                      </div>

                      {/* 移动端卡片展示 */}
                      <div className="md:hidden space-y-3">
                        {(() => {
                          // 使用后端返回的 chainType 和 inx 字段进行分组
                          const groupedResults = {
                            entry: diagnosisResult.results.filter(r => r.fromChainType === 1),
                            chains: {} as Record<number, typeof diagnosisResult.results>,
                            exit: diagnosisResult.results.filter(r => r.fromChainType === 3)
                          };
                          
                          // 按 inx 分组链路测试
                          diagnosisResult.results.forEach(r => {
                            if (r.fromChainType === 2 && r.fromInx != null) {
                              if (!groupedResults.chains[r.fromInx]) {
                                groupedResults.chains[r.fromInx] = [];
                              }
                              groupedResults.chains[r.fromInx].push(r);
                            }
                          });

                          const renderCardSection = (title: string, results: typeof diagnosisResult.results) => {
                            if (results.length === 0) return null;
                            
                            return (
                              <div key={title} className="space-y-2">
                                <div className="px-2 py-1.5 bg-primary/10 dark:bg-primary/20 rounded-lg border border-primary/30">
                                  <h3 className="text-sm font-semibold text-primary">{title}</h3>
                                </div>
                                {results.map((result, index) => {
                                  const quality = getQualityDisplay(result.averageTime, result.packetLoss);
                                  
                                  return (
                                    <div key={index} className={`border rounded-lg p-3 ${
                                      result.success 
                                        ? 'border-divider bg-white dark:bg-gray-800' 
                                        : 'border-danger-200 dark:border-danger-300/30 bg-danger-50 dark:bg-danger-900/30'
                                    }`}>
                                      <div className="flex items-start gap-2 mb-2">
                                        <span className={`w-6 h-6 rounded-full flex items-center justify-center text-xs flex-shrink-0 ${
                                          result.success ? 'bg-success text-white' : 'bg-danger text-white'
                                        }`}>
                                          {result.success ? '✓' : '✗'}
                                        </span>
                                        <div className="flex-1 min-w-0">
                                          <div className="font-semibold text-sm text-foreground break-words">
                                            {result.description}
                                          </div>
                                          <div className="text-xs text-default-500 mt-0.5 break-all">
                                            {result.targetIp}:{result.targetPort}
                                          </div>
                                        </div>
                                        <Chip 
                                          color={result.success ? 'success' : 'danger'} 
                                          variant="flat"
                                          size="sm"
                                          className="flex-shrink-0"
                                        >
                                          {result.success ? '成功' : '失败'}
                                        </Chip>
                                      </div>
                                      
                                      {result.success ? (
                                        <div className="grid grid-cols-3 gap-2 mt-2 pt-2 border-t border-divider">
                                          <div className="text-center">
                                            <div className="text-lg font-bold text-primary">
                                              {result.averageTime?.toFixed(0)}
                                            </div>
                                            <div className="text-xs text-default-500">延迟(ms)</div>
                                          </div>
                                          <div className="text-center">
                                            <div className={`text-lg font-bold ${
                                              (result.packetLoss || 0) > 0 ? 'text-warning' : 'text-success'
                                            }`}>
                                              {result.packetLoss?.toFixed(1)}%
                                            </div>
                                            <div className="text-xs text-default-500">丢包率</div>
                                          </div>
                                          <div className="text-center">
                                            {quality && (
                                              <>
                                                <Chip 
                                                  color={quality.color as any} 
                                                  variant="flat" 
                                                  size="sm"
                                                  className="text-xs"
                                                >
                                                  {quality.text}
                                                </Chip>
                                                <div className="text-xs text-default-500 mt-0.5">质量</div>
                                              </>
                                            )}
                                          </div>
                                        </div>
                                      ) : (
                                        <div className="mt-2 pt-2 border-t border-divider">
                                          <div className="text-xs text-danger">
                                            {result.message || '连接失败'}
                                          </div>
                                        </div>
                                      )}
                                    </div>
                                  );
                                })}
                              </div>
                            );
                          };

                          return (
                            <>
                              {/* 入口测试 */}
                              {renderCardSection('🚪 入口测试', groupedResults.entry)}
                              
                              {/* 链路测试（按跳数排序） */}
                              {Object.keys(groupedResults.chains)
                                .map(Number)
                                .sort((a, b) => a - b)
                                .map(hop => renderCardSection(`🔗 转发链 - 第${hop}跳`, groupedResults.chains[hop]))}
                              
                              {/* 出口测试 */}
                              {renderCardSection('🚀 出口测试', groupedResults.exit)}
                            </>
                          );
                        })()}
                      </div>

                      {/* 失败详情（仅桌面端显示，移动端已在卡片中显示） */}
                      {diagnosisResult.results.some(r => !r.success) && (
                        <div className="space-y-2 hidden md:block">
                          <h4 className="text-sm font-semibold text-danger">失败详情</h4>
                          <div className="space-y-2">
                            {diagnosisResult.results.filter(r => !r.success).map((result, index) => (
                              <Alert
                                key={index}
                                color="danger"
                                variant="flat"
                                title={result.description}
                                description={result.message || '连接失败'}
                                className="text-xs"
                              />
                            ))}
                          </div>
                        </div>
                      )}
                    </div>
                  ) : (
                    <div className="text-center py-16">
                      <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center mx-auto mb-4">
                        <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9.75 9.75l4.5 4.5m0-4.5l-4.5 4.5M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                      </div>
                      <h3 className="text-lg font-semibold text-foreground">暂无诊断数据</h3>
                    </div>
                  )}
                </ModalBody>
                <ModalFooter className="bg-content1 border-t border-divider">
                  <Button variant="light" onPress={onClose}>
                    关闭
                  </Button>
                  {currentDiagnosisForward && (
                    <Button 
                      color="primary" 
                      onPress={() => handleDiagnose(currentDiagnosisForward)}
                      isLoading={diagnosisLoading}
                    >
                      重新诊断
                    </Button>
                  )}
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>
      </div>
    
  );
} 
