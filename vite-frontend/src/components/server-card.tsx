import type { ReactNode } from 'react';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Chip } from '@heroui/chip';
import { Progress } from '@heroui/progress';
import toast from 'react-hot-toast';
import { translate } from '@/utils/locale';
import { copyText } from '@/utils/clipboard';

export const serverCardGrid = "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4";

export interface ServerCardNode {
  id: number;
  name: string;
  groupName?: string;
  ip: string;
  serverIp: string;
  detectedIpv4?: string;
  detectedIpv6?: string;
  port: string;
  tcpListenAddr?: string;
  udpListenAddr?: string;
  version?: string;
  http?: number; // 0 关 1 开
  tls?: number;  // 0 关 1 开
  socks?: number; // 0 关 1 开
  status: number; // 1: 在线, 0: 离线
  connectionStatus: 'online' | 'offline';
  systemInfo?: {
    cpuUsage: number;
    memoryUsage: number;
    uploadTraffic: number;
    downloadTraffic: number;
    uploadSpeed: number;
    downloadSpeed: number;
    uptime: number;
  } | null;
  copyLoading?: boolean;
  manageable?: boolean;
  allowIn?: boolean;
  allowOut?: boolean;
}

function NodeAddress({ value, placeholder, label }: { value?: string; placeholder: string; label: string }) {
  const address = value?.trim();
  if (!address) return <span className="font-mono text-xs">{translate(placeholder)}</span>;
  return <button type="button" className="font-mono text-xs truncate max-w-full text-right hover:text-primary cursor-pointer focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary rounded"
    aria-label={`${translate('复制')}${label}: ${address}`} title={`${address} · ${translate('点击复制')}`}
    onClick={async () => {
      if (await copyText(address)) toast.success(translate('IP已复制'));
      else toast.error(translate('复制失败，请手动选择文本复制'));
    }}>{address}</button>;
}

export function ServerCard({ node, selection, latency, actions }: { node: ServerCardNode; selection?: ReactNode; latency?: ReactNode; actions: ReactNode }) {
  // 格式化速度
  const formatSpeed = (bytesPerSecond: number): string => {
    if (bytesPerSecond === 0) return '0 B/s';

    const k = 1024;
    const sizes = ['B/s', 'KB/s', 'MB/s', 'GB/s', 'TB/s'];
    const i = Math.floor(Math.log(bytesPerSecond) / Math.log(k));

    return parseFloat((bytesPerSecond / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  // 格式化开机时间
  const formatUptime = (seconds: number): string => {
    if (seconds === 0) return '-';

    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);

    if (days > 0) {
      return `${days}天${hours}小时`;
    } else if (hours > 0) {
      return `${hours}小时${minutes}分钟`;
    } else {
      return `${minutes}分钟`;
    }
  };

  // 格式化流量
  const formatTraffic = (bytes: number): string => {
    if (bytes === 0) return '0 B';

    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));

    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  // 获取进度条颜色
  const getProgressColor = (value: number, offline = false): "default" | "primary" | "secondary" | "success" | "warning" | "danger" => {
    if (offline) return "default";
    if (value <= 50) return "success";
    if (value <= 80) return "warning";
    return "danger";
  };

  return (
              <Card
                data-server-card="true"
                className="shadow-sm border border-divider hover:shadow-md transition-shadow duration-200"
              >
                <CardHeader className="pb-2">
                  <div className="flex justify-between items-start w-full">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">{selection}<h3 className="font-semibold text-foreground truncate text-sm">{node.name}</h3></div>
                      {node.manageable === false && <p className="text-[11px] text-default-500">{translate("只读授权节点")}</p>}
                    </div>
                    <div className="flex items-center gap-1.5 ml-2">
                      {node.groupName && <Chip size="sm" variant="flat" className="mt-1 max-w-full" classNames={{ content: "truncate" }}>{node.groupName}</Chip>}
                      <Chip
                        color={node.connectionStatus === 'online' ? 'success' : 'danger'}
                        variant="flat"
                        size="sm"
                        className="text-xs"
                      >
                        {node.connectionStatus === 'online' ? translate("在线") : translate("离线")}
                      </Chip>
                    </div>
                  </div>
                </CardHeader>

                <CardBody className="pt-0 pb-3">
                  {/* 基础信息 */}
                  <div className="space-y-2 mb-4">
                    <div className="flex justify-between items-center text-sm min-w-0">
                      <span className="text-default-600 flex-shrink-0">IP</span>
                      <div className="text-right text-xs min-w-0 flex-1 ml-2">
                      <NodeAddress value={node.serverIp} placeholder="待获取" label="IP" />
                      </div>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">IPv4</span>
                      <NodeAddress value={node.detectedIpv4} placeholder="未获取" label="IPv4" />
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">IPv6</span>
                      <div className="min-w-0 ml-2 text-right"><NodeAddress value={node.detectedIpv6} placeholder="未获取" label="IPv6" /></div>
                    </div>
                    {latency}
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">{translate("版本")}</span>
                      <span className="text-xs">{node.version || '未知'}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">{translate("开机时间")}</span>
                      <span className="text-xs">
                        {node.connectionStatus === 'online' && node.systemInfo
                          ? formatUptime(node.systemInfo.uptime)
                          : '-'
                        }
                      </span>
                    </div>
                  </div>

                  {/* 系统监控 */}
                  <div className="space-y-3 mb-4">
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <div className="flex justify-between text-xs mb-1">
                          <span>CPU</span>
                          <span className="font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo
                              ? `${node.systemInfo.cpuUsage.toFixed(1)}%`
                              : '-'
                            }
                          </span>
                        </div>
                        <Progress
                          value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0}
                          color={getProgressColor(
                            node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0,
                            node.connectionStatus !== 'online'
                          )}
                          size="sm"
                          aria-label={translate("CPU使用率")}
                        />
                      </div>
                      <div>
                        <div className="flex justify-between text-xs mb-1">
                          <span>{translate("内存")}</span>
                          <span className="font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo
                              ? `${node.systemInfo.memoryUsage.toFixed(1)}%`
                              : '-'
                            }
                          </span>
                        </div>
                        <Progress
                          value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0}
                          color={getProgressColor(
                            node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0,
                            node.connectionStatus !== 'online'
                          )}
                          size="sm"
                          aria-label={translate("内存使用率")}
                        />
                      </div>
                    </div>

                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="text-center p-2 bg-default-50 dark:bg-default-100 rounded">
                        <div className="text-default-600 mb-0.5">{translate("上传")}</div>
                        <div className="font-mono">
                          {node.connectionStatus === 'online' && node.systemInfo
                            ? formatSpeed(node.systemInfo.uploadSpeed)
                            : '-'
                          }
                        </div>
                      </div>
                      <div className="text-center p-2 bg-default-50 dark:bg-default-100 rounded">
                        <div className="text-default-600 mb-0.5">{translate("下载")}</div>
                        <div className="font-mono">
                          {node.connectionStatus === 'online' && node.systemInfo
                            ? formatSpeed(node.systemInfo.downloadSpeed)
                            : '-'
                          }
                        </div>
                      </div>
                    </div>

                    {/* 流量统计 */}
                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="text-center p-2 bg-primary-50 dark:bg-primary-100/20 rounded border border-primary-200 dark:border-primary-300/20">
                        <div className="text-primary-600 dark:text-primary-400 mb-0.5">{translate("↑ 上行流量")}</div>
                        <div className="font-mono text-primary-700 dark:text-primary-300">
                          {node.connectionStatus === 'online' && node.systemInfo
                            ? formatTraffic(node.systemInfo.uploadTraffic)
                            : '-'
                          }
                        </div>
                      </div>
                      <div className="text-center p-2 bg-success-50 dark:bg-success-100/20 rounded border border-success-200 dark:border-success-300/20">
                        <div className="text-success-600 dark:text-success-400 mb-0.5">{translate("↓ 下行流量")}</div>
                        <div className="font-mono text-success-700 dark:text-success-300">
                          {node.connectionStatus === 'online' && node.systemInfo
                            ? formatTraffic(node.systemInfo.downloadTraffic)
                            : '-'
                          }
                        </div>
                      </div>
                    </div>
                  </div>

                  {actions}
                </CardBody>
              </Card>
  );
}
