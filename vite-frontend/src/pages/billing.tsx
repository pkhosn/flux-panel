import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Input } from '@heroui/input';
import { Select, SelectItem } from '@heroui/select';
import { Table, TableBody, TableCell, TableColumn, TableHeader, TableRow } from '@heroui/table';
import toast from 'react-hot-toast';
import {
  clearOrders,
  createPayOrder,
  deleteOrder,
  getAllOrderList,
  getMyOrderList,
  getPlanList,
  getUserPackageInfo,
  repayOrder,
  useRedeemCode,
} from '@/api';
import { isAdmin } from '@/utils/auth';
import { copyText } from '@/utils/clipboard';
import { getCachedConfig } from '@/config/site';

interface PlanItem {
  id: number;
  name: string;
  description?: string;
  price: number;
  flowGb: number;
  forwardNum: number;
  durationDays: number;
  status: number;
  stock: number;
}

interface OrderItem {
  id: number;
  orderNo: string;
  userName: string;
  planName: string;
  amount: number;
  payType: string;
  status: number;
  createdTime: number;
  paidTime?: number;
}

interface UserInfo {
  id?: number;
  name?: string;
  user?: string;
  status?: number;
  flow?: number;
  inFlow?: number;
  outFlow?: number;
  num?: number;
  expTime?: number | string;
  flowResetTime?: number;
  createdTime?: number;
  updatedTime?: number;
}

interface PaymentMethodOption {
  value: string;
  label: string;
  enabled: boolean;
}

const parsePaymentMethods = (raw?: string | null): PaymentMethodOption[] => {
  if (!raw || !raw.trim()) return [];
  const options: PaymentMethodOption[] = [];
  raw.split(',').forEach((segment) => {
    const text = segment.trim();
    if (!text) return;
    const pair = text.split(':');
    if (pair.length < 3) return;
    const value = (pair[0] || '').trim().toLowerCase();
    const label = (pair[1] || pair[0] || '').trim();
    const enabled = pair[2].trim() === '1';
    if (!value || !label) return;
    if (options.some((item) => item.value === value)) return;
    options.push({ value, label, enabled });
  });
  const enabledOptions = options.filter((item) => item.enabled);
  return enabledOptions;
};

export default function BillingPage() {
  const admin = isAdmin();

  const [plans, setPlans] = useState<PlanItem[]>([]);
  const [orders, setOrders] = useState<OrderItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [orderLoading, setOrderLoading] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [userInfo, setUserInfo] = useState<UserInfo | null>(null);

  const [selectedPlanId, setSelectedPlanId] = useState<string>('');
  const [payType, setPayType] = useState<string>('');
  const [paymentMethods, setPaymentMethods] = useState<PaymentMethodOption[]>([]);
  const [redeemCode, setRedeemCode] = useState('');

  const [filterKeyword, setFilterKeyword] = useState('');
  const [filterStatus, setFilterStatus] = useState<string>('all');

  useEffect(() => {
    if (!admin) {
      loadPlans();
      loadPackageInfo();
      loadPaymentMethods();
    }
    loadOrders();
  }, []);

  const loadPaymentMethods = async () => {
    try {
      const raw = await getCachedConfig('payment_methods');
      const parsed = parsePaymentMethods(raw);
      setPaymentMethods(parsed);
      if (!parsed.some((item) => item.value === payType)) {
        setPayType(parsed[0]?.value || '');
      }
    } catch (_e) {
      setPaymentMethods([]);
      setPayType('');
    }
  };

  const loadPackageInfo = async () => {
    try {
      const res = await getUserPackageInfo();
      if (res.code === 0) {
        setUserInfo(res.data?.userInfo || null);
      }
    } catch (_e) {
      // ignore non-critical failure
    }
  };

  const loadPlans = async () => {
    setLoading(true);
    try {
      const res = await getPlanList();
      if (res.code === 0) {
        setPlans((res.data || []).filter((item: PlanItem) => item.status === 1));
      } else {
        toast.error(res.msg || '获取套餐失败');
      }
    } catch (e) {
      toast.error('获取套餐失败');
    } finally {
      setLoading(false);
    }
  };

  const loadOrders = async () => {
    setOrderLoading(true);
    try {
      const res = admin ? await getAllOrderList() : await getMyOrderList();
      if (res.code === 0) {
        setOrders(res.data || []);
      } else {
        toast.error(res.msg || '获取订单失败');
      }
    } catch (e) {
      toast.error('获取订单失败');
    } finally {
      setOrderLoading(false);
    }
  };

  const selectedPlan = useMemo(() => plans.find((p) => String(p.id) === selectedPlanId), [plans, selectedPlanId]);
  const remainFlowGb = useMemo(() => {
    if (!userInfo || userInfo.flow === undefined || userInfo.flow === null) return null;
    if (Number(userInfo.flow) === 99999) return 99999;
    const totalBytes = Number(userInfo.flow) * 1024 * 1024 * 1024;
    const usedBytes = Number(userInfo.inFlow || 0) + Number(userInfo.outFlow || 0);
    const remainBytes = Math.max(totalBytes - usedBytes, 0);
    return Number((remainBytes / (1024 * 1024 * 1024)).toFixed(2));
  }, [userInfo]);

  const filteredOrders = useMemo(() => {
    if (!admin) {
      return orders;
    }
    return orders.filter((item) => {
      if (filterStatus !== 'all' && String(item.status) !== filterStatus) {
        return false;
      }
      if (!filterKeyword.trim()) {
        return true;
      }
      const key = filterKeyword.trim().toLowerCase();
      return (
        (item.orderNo || '').toLowerCase().includes(key) ||
        (item.userName || '').toLowerCase().includes(key) ||
        (item.planName || '').toLowerCase().includes(key)
      );
    });
  }, [admin, orders, filterKeyword, filterStatus]);

  const closePayWindow = (payWindow?: Window | null) => {
    if (payWindow && !payWindow.closed) {
      payWindow.close();
    }
  };

  const openPendingPayWindow = () => {
    const payWindow = window.open('', '_blank');
    if (payWindow) {
      payWindow.document.title = '正在跳转支付...';
      payWindow.document.body.innerHTML = '<div style="font-family:sans-serif;padding:16px;">正在跳转支付，请稍候...</div>';
    }
    return payWindow;
  };

  const openPayUrl = async (payUrl?: string, payWindow?: Window | null) => {
    if (!payUrl) {
      toast.error('未获取到支付链接');
      closePayWindow(payWindow);
      return;
    }
    if (payWindow && !payWindow.closed) {
      payWindow.location.replace(payUrl);
      return;
    }
    const opened = window.open(payUrl, '_blank');
    if (opened) {
      return;
    }
    const copied = await copyText(payUrl);
    if (copied) {
      toast.error('浏览器拦截了支付弹窗，已复制支付链接，请手动打开');
    } else {
      toast.error('浏览器拦截了支付弹窗，请允许弹窗后重试');
    }
  };

  const createOrder = async () => {
    if (paymentMethods.length === 0 || !payType) {
      toast.error('暂无可用支付方式，请联系管理员在网站配置中添加并启用支付');
      return;
    }
    if (!selectedPlanId) {
      toast.error('请选择套餐');
      return;
    }
    if (selectedPlan?.stock === 0) {
      toast.error('套餐已售罄，请选择其他套餐');
      return;
    }
    const payWindow = openPendingPayWindow();
    setSubmitLoading(true);
    try {
      const res = await createPayOrder({
        planId: Number(selectedPlanId),
        payType,
      });
      if (res.code === 0) {
        await openPayUrl(res.data?.payUrl, payWindow);
        toast.success('订单已创建，请完成支付');
        loadOrders();
      } else {
        closePayWindow(payWindow);
        toast.error(res.msg || '创建订单失败');
      }
    } catch (e) {
      closePayWindow(payWindow);
      toast.error('创建订单失败');
    } finally {
      setSubmitLoading(false);
    }
  };

  const continuePay = async (id: number) => {
    const payWindow = openPendingPayWindow();
    setSubmitLoading(true);
    try {
      const res = await repayOrder(id);
      if (res.code === 0) {
        await openPayUrl(res.data?.payUrl, payWindow);
      } else {
        closePayWindow(payWindow);
        toast.error(res.msg || '继续支付失败');
      }
    } catch (e) {
      closePayWindow(payWindow);
      toast.error('继续支付失败');
    } finally {
      setSubmitLoading(false);
    }
  };

  const redeem = async () => {
    if (!redeemCode.trim()) {
      toast.error('请输入兑换码');
      return;
    }
    setSubmitLoading(true);
    try {
      const res = await useRedeemCode(redeemCode.trim());
      if (res.code === 0) {
        toast.success('兑换成功，额度已发放');
        setRedeemCode('');
      } else {
        toast.error(res.msg || '兑换失败');
      }
    } catch (e) {
      toast.error('兑换失败');
    } finally {
      setSubmitLoading(false);
    }
  };

  const removeOrder = async (id: number) => {
    if (!window.confirm('确认删除该订单？')) {
      return;
    }
    const res = await deleteOrder(id);
    if (res.code === 0) {
      toast.success('订单已删除');
      loadOrders();
    } else {
      toast.error(res.msg || '删除失败');
    }
  };

  const clearOrderData = async () => {
    if (!window.confirm('确认按当前筛选条件清理订单？')) {
      return;
    }
    const payload: { status?: number; keyword?: string } = {};
    if (filterStatus !== 'all') {
      payload.status = Number(filterStatus);
    }
    if (filterKeyword.trim()) {
      payload.keyword = filterKeyword.trim();
    }
    const res = await clearOrders(payload);
    if (res.code === 0) {
      toast.success('订单已清理');
      loadOrders();
    } else {
      toast.error(res.msg || '清理失败');
    }
  };

  const statusText = (status: number) => {
    if (status === 1) return '已支付';
    if (status === 2) return '已关闭';
    return '待支付';
  };

  const formatDateTime = (value?: number | string) => {
    if (value === undefined || value === null || value === '' || Number(value) <= 0) {
      return '-';
    }
    return new Date(Number(value)).toLocaleString();
  };

  const formatFlowLimit = (value?: number) => {
    if (value === undefined || value === null) return '-';
    if (Number(value) === 99999) return '无限制';
    return `${value} GB`;
  };

  const formatResetDay = (value?: number) => {
    if (value === undefined || value === null) return '-';
    return value === 0 ? '不重置' : `每月${value}号`;
  };

  const packageStatus = useMemo(() => {
    if (!userInfo) return '未开通';
    const exp = Number(userInfo.expTime || 0);
    if (exp <= 0) return '未开通';
    return exp > Date.now() ? '有效中' : '已过期';
  }, [userInfo]);

  return (
    <div className="p-4 md:p-6 space-y-6">
      {!admin && (
        <>
          <Card>
            <CardHeader className="text-lg font-semibold">当前套餐信息</CardHeader>
            <CardBody className="space-y-2">
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-x-6 gap-y-2 text-sm leading-6">
                <div className="flex items-center gap-2">
                  <span className="text-default-500 shrink-0">用户名:</span>
                  <span className="text-default-900 truncate">{userInfo?.user || userInfo?.name || localStorage.getItem('name') || '-'}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-default-500 shrink-0">套餐状态:</span>
                  <span className={packageStatus === '有效中' ? 'text-success' : 'text-danger'}>{packageStatus}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-default-500 shrink-0">到期时间:</span>
                  <span className="text-default-900 truncate">{formatDateTime(userInfo?.expTime)}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-default-500 shrink-0">流量配额:</span>
                  <span className="text-default-900">{formatFlowLimit(userInfo?.flow)}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-default-500 shrink-0">剩余流量:</span>
                  <span className="text-default-900">{remainFlowGb === 99999 ? '无限制' : `${remainFlowGb ?? '-'} GB`}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-default-500 shrink-0">规则数配额:</span>
                  <span className="text-default-900">{userInfo?.num ?? '-'} 条</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-default-500 shrink-0">流量重置日:</span>
                  <span className="text-default-900">{formatResetDay(userInfo?.flowResetTime)}</span>
                </div>
              </div>

              {packageStatus !== '有效中' && (
                <div className="text-sm text-danger">
                  {packageStatus === '已过期' ? '当前套餐已过期，请先续费后再使用购买/兑换功能。' : '当前没有有效套餐。'}
                </div>
              )}
            </CardBody>
          </Card>
          <Card>
            <CardHeader className="text-lg font-semibold">购买套餐</CardHeader>
            <CardBody className="space-y-4">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                <Select
                  label="选择套餐"
                  placeholder="请选择套餐"
                  selectedKeys={selectedPlanId ? new Set([selectedPlanId]) : new Set()}
                  onSelectionChange={(keys) => {
                    if (keys === 'all') {
                      setSelectedPlanId('');
                      return;
                    }
                    setSelectedPlanId(String(Array.from(keys)[0] || ''));
                  }}
                  isDisabled={loading}
                >
                  {plans.map((plan) => (
                    <SelectItem
                      key={String(plan.id)}
                      textValue={`${plan.name} - ¥${plan.price}${plan.stock === 0 ? '（售罄）' : ''}`}
                      isDisabled={plan.stock === 0}
                    >
                      {plan.name} - ¥{plan.price}{plan.stock === 0 ? '（售罄）' : ''}
                    </SelectItem>
                  ))}
                </Select>
                <Select
                  label="支付方式"
                  selectedKeys={[payType]}
                  onSelectionChange={(keys) => setPayType(String(Array.from(keys)[0] || paymentMethods[0]?.value || 'alipay'))}
                >
                  {paymentMethods.map((item) => (
                    <SelectItem key={item.value}>{item.label}</SelectItem>
                  ))}
                </Select>
                <Button
                  color="primary"
                  onPress={createOrder}
                  isLoading={submitLoading}
                  className="h-14"
                  isDisabled={!selectedPlanId || selectedPlan?.stock === 0}
                >
                  {selectedPlan?.stock === 0 ? '已售罄' : '立即下单'}
                </Button>
              </div>
              {selectedPlan && (
                <div className="text-sm text-default-500">
                  套餐内容: {selectedPlan.flowGb}GB 流量 / {selectedPlan.forwardNum} 条转发 / {selectedPlan.durationDays} 天
                  {selectedPlan.stock === -1 ? ' / 库存不限' : ` / 剩余库存 ${selectedPlan.stock}`}
                </div>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader className="text-lg font-semibold">兑换码</CardHeader>
            <CardBody className="grid grid-cols-1 md:grid-cols-[1fr_auto] gap-3">
              <Input label="输入兑换码" placeholder="例如 FXABCD1234..." value={redeemCode} onValueChange={setRedeemCode} />
              <Button color="secondary" onPress={redeem} isLoading={submitLoading} className="h-14">立即兑换</Button>
            </CardBody>
          </Card>
        </>
      )}

      <Card>
        <CardHeader className="flex justify-between">
          <div className="text-lg font-semibold">{admin ? '订单管理' : '我的订单'}</div>
          <div className="flex gap-2">
            {admin && (
              <Button size="sm" color="danger" variant="flat" onPress={clearOrderData}>
                清理订单
              </Button>
            )}
            <Button size="sm" variant="flat" onPress={loadOrders} isLoading={orderLoading}>刷新</Button>
          </div>
        </CardHeader>
        <CardBody className="space-y-3">
          {admin && (
            <div className="grid grid-cols-1 md:grid-cols-[1fr_180px_auto] gap-2">
              <Input
                placeholder="筛选: 订单号/用户名/套餐名"
                value={filterKeyword}
                onValueChange={setFilterKeyword}
              />
              <Select label="状态筛选" selectedKeys={[filterStatus]} onSelectionChange={(keys) => setFilterStatus(String(Array.from(keys)[0] || 'all'))}>
                <SelectItem key="all">全部状态</SelectItem>
                <SelectItem key="0">待支付</SelectItem>
                <SelectItem key="1">已支付</SelectItem>
                <SelectItem key="2">已关闭</SelectItem>
              </Select>
              <Button variant="flat" onPress={() => { setFilterKeyword(''); setFilterStatus('all'); }}>
                重置筛选
              </Button>
            </div>
          )}

          {admin ? (
            <Table aria-label="订单列表" isStriped>
              <TableHeader>
                <TableColumn>订单号</TableColumn>
                <TableColumn>用户</TableColumn>
                <TableColumn>套餐</TableColumn>
                <TableColumn>金额</TableColumn>
                <TableColumn>支付方式</TableColumn>
                <TableColumn>状态</TableColumn>
                <TableColumn>创建时间</TableColumn>
                <TableColumn>操作</TableColumn>
              </TableHeader>
              <TableBody isLoading={orderLoading} emptyContent="暂无订单">
                {filteredOrders.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell>{item.orderNo}</TableCell>
                    <TableCell>{item.userName}</TableCell>
                    <TableCell>{item.planName}</TableCell>
                    <TableCell>{item.amount}</TableCell>
                    <TableCell>{item.payType}</TableCell>
                    <TableCell>{statusText(item.status)}</TableCell>
                    <TableCell>{new Date(item.createdTime).toLocaleString()}</TableCell>
                    <TableCell>
                      <Button size="sm" color="danger" variant="flat" onPress={() => removeOrder(item.id)}>
                        删除
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <Table aria-label="订单列表" isStriped>
              <TableHeader>
                <TableColumn>订单号</TableColumn>
                <TableColumn>套餐</TableColumn>
                <TableColumn>金额</TableColumn>
                <TableColumn>支付方式</TableColumn>
                <TableColumn>状态</TableColumn>
                <TableColumn>创建时间</TableColumn>
                <TableColumn>操作</TableColumn>
              </TableHeader>
              <TableBody isLoading={orderLoading} emptyContent="暂无订单">
                {orders.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell>{item.orderNo}</TableCell>
                    <TableCell>{item.planName}</TableCell>
                    <TableCell>{item.amount}</TableCell>
                    <TableCell>{item.payType}</TableCell>
                    <TableCell>{statusText(item.status)}</TableCell>
                    <TableCell>{new Date(item.createdTime).toLocaleString()}</TableCell>
                    <TableCell>
                      {item.status === 0 ? (
                        <Button size="sm" color="primary" variant="flat" isLoading={submitLoading} onPress={() => continuePay(item.id)}>
                          继续支付
                        </Button>
                      ) : (
                        '-'
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardBody>
      </Card>
    </div>
  );
}
