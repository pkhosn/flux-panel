import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Input } from "@heroui/input";
import { Spinner } from "@heroui/spinner";
import { Divider } from "@heroui/divider";
import { Switch } from "@heroui/switch";
import { Select, SelectItem } from "@heroui/select";
import toast from 'react-hot-toast';
import { updateConfigs } from '@/api';
import { SettingsIcon } from '@/components/icons';

import { isAdmin } from '@/utils/auth';
import { getCachedConfigs, clearConfigCache, updateSiteConfig } from '@/config/site';

// 简单的保存图标组件
const SaveIcon = ({ className }: { className?: string }) => (
  <svg
    className={className}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
    <polyline points="17,21 17,13 7,13 7,21" />
    <polyline points="7,3 7,8 15,8" />
  </svg>
);

interface ConfigItem {
  key: string;
  label: string;
  placeholder?: string;
  description?: string;
  type: 'input' | 'switch' | 'select';
  options?: { label: string; value: string; description?: string }[];
  dependsOn?: string; // 依赖的配置项key
  dependsValue?: string; // 依赖的配置项值
}

interface PaymentMethodItem {
  id: string;
  value: string;
  label: string;
}

const parsePaymentMethods = (raw?: string): PaymentMethodItem[] => {
  if (!raw || !raw.trim()) return [];
  return raw
    .split(',')
    .map((item, index) => {
      const text = item.trim();
      if (!text) return null;
      const [value, label] = text.split(':');
      const v = (value || '').trim();
      const l = (label || value || '').trim();
      if (!v || !l) return null;
      return { id: `${Date.now()}_${index}`, value: v, label: l };
    })
    .filter((item): item is PaymentMethodItem => item !== null);
};

const serializePaymentMethods = (items: PaymentMethodItem[]): string => {
  return items
    .map((item) => `${item.value.trim()}:${item.label.trim()}`)
    .filter((item) => item !== ':')
    .join(',');
};

// 网站配置项定义
const CONFIG_ITEMS: ConfigItem[] = [
  {
    key: 'ip',
    label: '面板后端地址',
    placeholder: '请输入面板后端IP:PORT',
    description: '格式“ip:port”,用于对接节点时使用,ip是你安装面板服务器的公网ip,端口是安装脚本内输入的后端端口。不要套CDN,不支持https,通讯数据有加密',
    type: 'input'
  },
  {
    key: 'app_name',
    label: '应用名称',
    placeholder: '请输入应用名称',
    description: '在浏览器标签页和导航栏显示的应用名称',
    type: 'input'
  },
  {
    key: 'captcha_enabled',
    label: '启用验证码',
    description: '开启后，用户登录时需要完成验证码验证',
    type: 'switch'
  },
  {
    key: 'captcha_type',
    label: '验证码类型',
    description: '选择验证码的显示类型，不同类型有不同的安全级别',
    type: 'select',
    dependsOn: 'captcha_enabled',
    dependsValue: 'true',
    options: [
      { 
        label: '随机类型', 
        value: 'RANDOM', 
        description: '系统随机选择验证码类型' 
      },
      { 
        label: '滑块验证码', 
        value: 'SLIDER', 
        description: '拖动滑块完成拼图验证' 
      },
      { 
        label: '文字点选验证码', 
        value: 'WORD_IMAGE_CLICK', 
        description: '按顺序点击指定文字' 
      },
      { 
        label: '旋转验证码', 
        value: 'ROTATE', 
        description: '旋转图片到正确角度' 
      },
      { 
        label: '拼图验证码', 
        value: 'CONCAT', 
        description: '拖动滑块完成图片拼接' 
      }
    ]
  },
  {
    key: 'payment_enabled',
    label: '启用支付',
    description: '开启后用户可创建支付订单',
    type: 'switch'
  },
  {
    key: 'payment_provider',
    label: '支付通道',
    description: '当前支持 MGate 兼容通道',
    type: 'select',
    dependsOn: 'payment_enabled',
    dependsValue: 'true',
    options: [
      {
        label: 'MGate',
        value: 'mgate',
        description: '使用 MGate API 创建支付并处理回调'
      },
      {
        label: 'EPay',
        value: 'epay',
        description: '易支付兼容通道'
      },
      {
        label: 'BEPUSDT',
        value: 'bepusdt',
        description: 'BEPUSDT 通道'
      },
      {
        label: 'BTCPay',
        value: 'btcpay',
        description: 'BTCPay Server'
      },
      {
        label: 'CoinPayments',
        value: 'coinpayments',
        description: 'CoinPayments 加密货币支付'
      },
      {
        label: 'Coinbase',
        value: 'coinbase',
        description: 'Coinbase Commerce'
      },
      {
        label: 'Stripe Checkout',
        value: 'stripe_checkout',
        description: 'Stripe Checkout'
      },
      {
        label: 'Stripe Alipay',
        value: 'stripe_alipay',
        description: 'Stripe 支付宝'
      },
      {
        label: 'Stripe WePay',
        value: 'stripe_wepay',
        description: 'Stripe 微信'
      },
      {
        label: 'Stripe Credit',
        value: 'stripe_credit',
        description: 'Stripe 信用卡'
      },
      {
        label: 'Stripe ALL',
        value: 'stripe_all',
        description: 'Stripe 多支付方式'
      }
    ]
  },
  {
    key: 'payment_mgate_url',
    label: 'MGate API 地址',
    placeholder: '如: https://pay.example.com',
    description: '支付网关基础地址，不包含 /v1/gateway/fetch',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'mgate'
  },
  {
    key: 'payment_mgate_app_id',
    label: 'MGate App ID',
    placeholder: '请输入 app_id',
    description: 'MGate 分配的应用 ID',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'mgate'
  },
  {
    key: 'payment_mgate_app_secret',
    label: 'MGate App Secret',
    placeholder: '请输入 app_secret',
    description: '用于签名与回调验签',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'mgate'
  },
  {
    key: 'payment_mgate_source_currency',
    label: 'MGate 源货币',
    placeholder: '默认 CNY',
    description: '下单时传递给网关的 source_currency',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'mgate'
  },
  {
    key: 'payment_notify_base_url',
    label: '支付回调基础地址',
    placeholder: '如: http://your-domain:6365',
    description: '后端可被支付网关访问的地址，系统会拼接 /api/v1/order/notify/mgate',
    type: 'input',
    dependsOn: 'payment_enabled',
    dependsValue: 'true'
  },
  {
    key: 'payment_epay_url',
    label: 'EPay 地址',
    placeholder: '如: https://epay.example.com',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'epay'
  },
  {
    key: 'payment_epay_pid',
    label: 'EPay PID',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'epay'
  },
  {
    key: 'payment_epay_key',
    label: 'EPay KEY',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'epay'
  },
  {
    key: 'payment_bepusdt_url',
    label: 'BEPUSDT 地址',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'bepusdt'
  },
  {
    key: 'payment_bepusdt_apitoken',
    label: 'BEPUSDT Token',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'bepusdt'
  },
  {
    key: 'payment_btcpay_url',
    label: 'BTCPay 地址',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'btcpay'
  },
  {
    key: 'payment_btcpay_store_id',
    label: 'BTCPay Store ID',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'btcpay'
  },
  {
    key: 'payment_btcpay_api_key',
    label: 'BTCPay API Key',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'btcpay'
  },
  {
    key: 'payment_btcpay_webhook_key',
    label: 'BTCPay Webhook Key',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'btcpay'
  },
  {
    key: 'payment_btcpay_currency',
    label: 'BTCPay 货币',
    placeholder: '默认 USD',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'btcpay'
  },
  {
    key: 'payment_coinpayments_merchant_id',
    label: 'CoinPayments Merchant ID',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'coinpayments'
  },
  {
    key: 'payment_coinpayments_ipn_secret',
    label: 'CoinPayments IPN Secret',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'coinpayments'
  },
  {
    key: 'payment_coinbase_url',
    label: 'Coinbase Endpoint',
    placeholder: '如: https://api.commerce.coinbase.com/charges',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'coinbase'
  },
  {
    key: 'payment_coinbase_api_key',
    label: 'Coinbase API Key',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'coinbase'
  },
  {
    key: 'payment_coinbase_webhook_key',
    label: 'Coinbase Webhook Key',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'coinbase'
  },
  {
    key: 'payment_stripe_sk_live',
    label: 'Stripe Secret Key',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'stripe_checkout'
  },
  {
    key: 'payment_stripe_currency',
    label: 'Stripe 货币',
    placeholder: '如 usd',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'stripe_checkout'
  },
  {
    key: 'payment_stripe_webhook_key',
    label: 'Stripe Webhook Key',
    type: 'input',
    dependsOn: 'payment_provider',
    dependsValue: 'stripe_checkout'
  },
  {
    key: 'payment_return_base_url',
    label: '支付返回基础地址',
    placeholder: '如: http://your-domain:6366',
    description: '支付完成后跳转前端地址，系统会拼接 /billing?orderNo=xxx',
    type: 'input',
    dependsOn: 'payment_enabled',
    dependsValue: 'true'
  },
  {
    key: 'payment_methods',
    label: '支付方式配置',
    placeholder: '如: alipay:支付宝,wxpay:微信支付,qqpay:QQ支付',
    description: '格式: 渠道值:显示名，多个用英文逗号分隔。示例 alipay:支付宝,wxpay:微信支付',
    type: 'input',
    dependsOn: 'payment_enabled',
    dependsValue: 'true'
  }
];

// 初始化时从缓存读取配置，避免闪烁
const getInitialConfigs = (): Record<string, string> => {
  if (typeof window === 'undefined') return {};
  
  const configKeys = [
    'app_name',
    'captcha_enabled',
    'captcha_type',
    'ip',
    'payment_enabled',
    'payment_provider',
    'payment_mgate_url',
    'payment_mgate_app_id',
    'payment_mgate_app_secret',
    'payment_mgate_source_currency',
    'payment_notify_base_url',
    'payment_return_base_url',
    'payment_methods',
    'payment_epay_url',
    'payment_epay_pid',
    'payment_epay_key',
    'payment_bepusdt_url',
    'payment_bepusdt_apitoken',
    'payment_btcpay_url',
    'payment_btcpay_store_id',
    'payment_btcpay_api_key',
    'payment_btcpay_webhook_key',
    'payment_btcpay_currency',
    'payment_coinpayments_merchant_id',
    'payment_coinpayments_ipn_secret',
    'payment_coinbase_url',
    'payment_coinbase_api_key',
    'payment_coinbase_webhook_key',
    'payment_stripe_sk_live',
    'payment_stripe_currency',
    'payment_stripe_webhook_key'
  ];
  const initialConfigs: Record<string, string> = {};
  
  try {
    configKeys.forEach(key => {
      const cachedValue = localStorage.getItem('vite_config_' + key);
      if (cachedValue) {
        initialConfigs[key] = cachedValue;
      }
    });
  } catch (error) {
  }
  
  return initialConfigs;
};

export default function ConfigPage() {
  const navigate = useNavigate();
  const initialConfigs = getInitialConfigs();
  const [configs, setConfigs] = useState<Record<string, string>>(initialConfigs);
  const [loading, setLoading] = useState(Object.keys(initialConfigs).length === 0); // 如果有缓存数据，不显示loading
  const [saving, setSaving] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);
  const [originalConfigs, setOriginalConfigs] = useState<Record<string, string>>(initialConfigs);
  const [paymentMethodsEditor, setPaymentMethodsEditor] = useState<PaymentMethodItem[]>([]);

  // 权限检查
  useEffect(() => {
    if (!isAdmin()) {
      toast.error('权限不足，只有管理员可以访问此页面');
      navigate('/dashboard', { replace: true });
      return;
    }
  }, [navigate]);

  // 加载配置数据（优先从缓存）
  const loadConfigs = async (currentConfigs?: Record<string, string>) => {
    const configsToCompare = currentConfigs || configs;
    const hasInitialData = Object.keys(configsToCompare).length > 0;
    
    // 如果已有缓存数据，不显示loading，静默更新
    if (!hasInitialData) {
      setLoading(true);
    }
    
    try {
      const configData = await getCachedConfigs();
      
      // 只有在数据有变化时才更新
      const hasDataChanged = JSON.stringify(configData) !== JSON.stringify(configsToCompare);
      if (hasDataChanged) {
        setConfigs(configData);
        setOriginalConfigs({ ...configData });
        setHasChanges(false);
      } else {
      }
    } catch (error) {
      // 只有在没有缓存数据时才显示错误
      if (!hasInitialData) {
        toast.error('加载配置出错，请重试');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // 延迟加载，避免阻塞初始渲染
    const timer = setTimeout(() => {
      loadConfigs(initialConfigs);
    }, 100);

    return () => clearTimeout(timer);
  }, []); // 只在组件挂载时执行一次

  useEffect(() => {
    setPaymentMethodsEditor(parsePaymentMethods(configs.payment_methods || ''));
  }, [configs.payment_methods]);

  // 处理配置项变更
  const handleConfigChange = (key: string, value: string) => {
    let newConfigs = { ...configs, [key]: value };
    
    // 特殊处理：启用验证码时，如果验证码类型未设置，默认为随机
    if (key === 'captcha_enabled' && value === 'true') {
      if (!newConfigs.captcha_type) {
        newConfigs.captcha_type = 'RANDOM';
      }
    }

    if (key === 'payment_enabled' && value === 'true') {
      if (!newConfigs.payment_provider) {
        newConfigs.payment_provider = 'mgate';
      }
      if (!newConfigs.payment_mgate_source_currency) {
        newConfigs.payment_mgate_source_currency = 'CNY';
      }
    }
    
    setConfigs(newConfigs);
    
    // 检查是否有变更
    const hasChangesNow = Object.keys(newConfigs).some(
      k => newConfigs[k] !== originalConfigs[k]
    ) || Object.keys(originalConfigs).some(
      k => originalConfigs[k] !== newConfigs[k]
    );
    setHasChanges(hasChangesNow);
  };

  // 保存配置
  const handleSave = async () => {
    setSaving(true);
    try {
      const response = await updateConfigs(configs);
      if (response.code === 0) {
        toast.success('配置保存成功');
        
        // 清除所有配置缓存，强制下次重新获取
        clearConfigCache();
        
        // 获取变更的配置项
        const changedKeys = Object.keys(configs).filter(
          key => configs[key] !== originalConfigs[key]
        );
        
        setOriginalConfigs({ ...configs });
        setHasChanges(false);
        
        // 如果应用名称发生变化，立即更新网站配置
        if (changedKeys.includes('app_name')) {
          await updateSiteConfig();
        }
        
        // 触发配置更新事件，通知其他组件
        window.dispatchEvent(new CustomEvent('configUpdated', { 
          detail: { changedKeys } 
        }));
      } else {
        toast.error('保存配置失败: ' + response.msg);
      }
    } catch (error) {
      toast.error('保存配置出错，请重试');
    } finally {
      setSaving(false);
    }
  };

  const updatePaymentMethods = (list: PaymentMethodItem[]) => {
    setPaymentMethodsEditor(list);
    handleConfigChange('payment_methods', serializePaymentMethods(list));
  };

  const addPaymentMethod = () => {
    updatePaymentMethods([
      ...paymentMethodsEditor,
      { id: `${Date.now()}_${Math.random()}`, value: '', label: '' }
    ]);
  };

  const removePaymentMethod = (id: string) => {
    updatePaymentMethods(paymentMethodsEditor.filter((item) => item.id !== id));
  };

  const updatePaymentMethodField = (id: string, field: 'value' | 'label', value: string) => {
    const next = paymentMethodsEditor.map((item) => {
      if (item.id !== id) return item;
      return { ...item, [field]: value };
    });
    updatePaymentMethods(next);
  };



  // 检查配置项是否应该显示（依赖检查）
  const shouldShowItem = (item: ConfigItem): boolean => {
    if (!item.dependsOn || !item.dependsValue) {
      return true;
    }
    return configs[item.dependsOn] === item.dependsValue;
  };

  // 渲染不同类型的配置项
  const renderConfigItem = (item: ConfigItem) => {
    const isChanged = hasChanges && configs[item.key] !== originalConfigs[item.key];
    
    switch (item.type) {
      case 'input':
        if (item.key === 'payment_methods') {
          return (
            <div className="space-y-3">
              {paymentMethodsEditor.length === 0 && (
                <div className="text-sm text-default-500">暂未配置支付方式，可点击“新增支付方式”。</div>
              )}
              {paymentMethodsEditor.map((method) => (
                <div key={method.id} className="grid grid-cols-1 md:grid-cols-[1fr_1fr_auto] gap-2">
                  <Input
                    value={method.value}
                    onChange={(e) => updatePaymentMethodField(method.id, 'value', e.target.value)}
                    placeholder="渠道值，如 alipay"
                    variant="bordered"
                    size="md"
                  />
                  <Input
                    value={method.label}
                    onChange={(e) => updatePaymentMethodField(method.id, 'label', e.target.value)}
                    placeholder="显示名，如 支付宝"
                    variant="bordered"
                    size="md"
                  />
                  <Button color="danger" variant="flat" onClick={() => removePaymentMethod(method.id)}>
                    删除
                  </Button>
                </div>
              ))}
              <Button color="primary" variant="flat" onClick={addPaymentMethod}>
                新增支付方式
              </Button>
            </div>
          );
        }
        return (
          <Input
            value={configs[item.key] || ''}
            onChange={(e) => handleConfigChange(item.key, e.target.value)}
            placeholder={item.placeholder}
            variant="bordered"
            size="md"
            classNames={{
              input: "text-sm",
              inputWrapper: isChanged 
                ? "border-warning-300 data-[hover=true]:border-warning-400" 
                : ""
            }}
          />
        );

      case 'switch':
        return (
          <Switch
            isSelected={configs[item.key] === 'true'}
            onValueChange={(checked) => handleConfigChange(item.key, checked ? 'true' : 'false')}
            color="primary"
            size="md"
            classNames={{
              wrapper: isChanged ? "border-warning-300" : ""
            }}
          >
            <span className="text-sm text-gray-700 dark:text-gray-300">
              {configs[item.key] === 'true' ? '已启用' : '已禁用'}
            </span>
          </Switch>
        );

      case 'select':
        return (
          <Select
            selectedKeys={configs[item.key] ? [configs[item.key]] : []}
            onSelectionChange={(keys) => {
              const selectedKey = Array.from(keys)[0] as string;
              if (selectedKey) {
                handleConfigChange(item.key, selectedKey);
              }
            }}
            placeholder="请选择验证码类型"
            variant="bordered"
            size="md"
            classNames={{
              trigger: isChanged 
                ? "border-warning-300 data-[hover=true]:border-warning-400" 
                : ""
            }}
          >
            {item.options?.map((option) => (
              <SelectItem 
                key={option.value}
                description={option.description}
              >
                {option.label}
              </SelectItem>
            )) || []}
          </Select>
        );

      default:
        return null;
    }
  };

  if (loading) {
    return (
      
        <div className="flex items-center justify-center min-h-[400px]">
          <Spinner size="lg" label="加载配置中..." />
        </div>
      
    );
  }

  return (
    
      <div className="p-6 max-w-4xl mx-auto">
        {/* 页面标题 */}
        <div className="flex items-center gap-3 mb-6">
          <SettingsIcon className="w-8 h-8 text-primary" />
          <div>
            <h1 className="text-2xl font-bold">网站配置</h1>
            <p className="text-gray-600 dark:text-gray-400">
              管理网站的基本信息和显示设置
            </p>
          </div>
        </div>

        <Card className="shadow-md">
          <CardHeader className="pb-4">
            <div className="flex justify-between items-center w-full">
              <div>
                <h2 className="text-xl font-semibold">基本设置</h2>
                <p className="text-sm text-gray-600 dark:text-gray-400">
                  配置网站的基本信息，这些设置会影响网站的显示效果
                </p>
              </div>
              <div className="flex gap-2">

                <Button
                  color="primary"
                  startContent={<SaveIcon className="w-4 h-4" />}
                  onClick={handleSave}
                  isLoading={saving}
                  disabled={!hasChanges}
                >
                  {saving ? '保存中...' : '保存配置'}
                </Button>
              </div>
            </div>
          </CardHeader>

          <Divider />

          <CardBody className="space-y-6 pt-6">
            {CONFIG_ITEMS.map((item, index) => {
              // 检查配置项是否应该显示
              if (!shouldShowItem(item)) {
                return null;
              }

              // 计算是否是最后一个显示的项目（用于决定是否显示分隔线）
              const remainingItems = CONFIG_ITEMS.slice(index + 1).filter(shouldShowItem);
              const isLastItem = remainingItems.length === 0;

              return (
                <div key={item.key} className="space-y-3">
                  <div className="flex flex-col gap-1">
                    <label className="text-sm font-medium text-gray-700 dark:text-gray-300">
                      {item.label}
                    </label>
                    {item.description && (
                      <p className="text-xs text-gray-500 dark:text-gray-400">
                        {item.description}
                      </p>
                    )}
                  </div>
                  
                  {/* 渲染配置项 */}
                  {renderConfigItem(item)}
                  
                  {/* 分隔线 */}
                  {!isLastItem && (
                    <Divider className="mt-6" />
                  )}
                </div>
              );
            })}
          </CardBody>
        </Card>

        {/* 操作提示 */}
        {hasChanges && (
          <Card className="mt-4 bg-warning-50 dark:bg-warning-900/20 border-warning-200 dark:border-warning-800">
            <CardBody className="py-3">
              <div className="flex items-center gap-2 text-warning-700 dark:text-warning-300">
                <div className="w-2 h-2 bg-warning-500 rounded-full animate-pulse" />
                <span className="text-sm">
                  检测到配置变更，请记得保存您的修改
                </span>
              </div>
            </CardBody>
          </Card>
        )}
      </div>
    
  );
} 
