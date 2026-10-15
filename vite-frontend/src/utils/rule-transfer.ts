export interface TransferRule {
  name: string;
  tunnelId: number;
  remoteAddr: string;
  inPort: number | null;
  protocol: "tcp" | "udp" | "tcp_udp";
  strategy: string;
  groupId: number;
  proxyAccept: number;
  proxySend: number;
  proxyTrustedCidrs: string;
}

const object = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);

export function parseRuleTransfer(text: string, tunnelId: number): TransferRule[] {
  if (new TextEncoder().encode(text).length > 1024 * 1024) throw Error("导入内容不能超过 1 MiB");
  const content = text.replace(/^\uFEFF/, "").trim();
  let data: unknown;
  try { data = JSON.parse(content); }
  catch {
    data = content.split(/\r?\n/).filter(line => line.trim()).map((line, index) => {
      try { return JSON.parse(line); } catch { throw Error(`第 ${index + 1} 行不是有效 JSON`); }
    });
  }
  const rows = Array.isArray(data) ? data : object(data) && Array.isArray(data.rules) ? data.rules : [data];
  if (!rows.length || rows.length > 500) throw Error("每次导入 1–500 条规则");
  return rows.map((row, index) => {
    const fail = (reason: string): never => { throw Error(`第 ${index + 1} 条：${reason}`); };
    if (!object(row)) return fail("规则必须是 JSON 对象");
    if (row.tls_inbound_id || (object(row.origin_tls) && row.origin_tls.enabled)) return fail("共享 TLS 和回源 TLS 尚未支持导入");
    if (row.enabled === false || row.status === 0) return fail("暂停规则请先恢复后导入");
    const protocol = row.protocol ?? (row.dest ? "tcp" : "tcp_udp");
    if (!["tcp", "udp", "tcp_udp"].includes(String(protocol))) return fail("协议无效");
    const targets = row.dest ?? row.targets;
    const remoteAddr = Array.isArray(targets) && targets.every(value => typeof value === "string")
      ? targets.join(",") : row.remoteAddr;
    if (typeof remoteAddr !== "string" || !remoteAddr.trim()) return fail("目标地址不能为空");
    for (const address of remoteAddr.split(",")) {
      const match = /^(?:\[[0-9a-fA-F:]+\]|[^\s:,/]+):(\d+)$/.exec(address.trim());
      if (!match || Number(match[1]) < 1 || Number(match[1]) > 65535) return fail("目标必须为主机:端口或 [IPv6]:端口");
    }
    if (typeof row.name !== "string" || !row.name.trim()) return fail("名称不能为空");
    const port = row.inPort ?? row.listen_port ?? 0;
    if (!Number.isInteger(port) || Number(port) < 0 || Number(port) > 65535) return fail("监听端口必须为 0–65535 的整数");
    const sendModes: Record<string, number> = { off: 0, v1: 1, v2: 2 };
    const nyanModes: Record<string, number> = { "0": 0, "1": 1, "2": 2, "3": 2 };
    const proxySend = row.proxySend ?? (row.proxy_protocol !== undefined ? nyanModes[String(row.proxy_protocol)] : sendModes[String(row.proxy_send ?? "off")]);
    const proxyAccept = row.proxyAccept ?? (row.accept_proxy_protocol !== undefined ? row.accept_proxy_protocol : ["auto", "v1", "v2"].includes(String(row.proxy_accept)) ? 1 : 0);
    if (![0, 1, 2].includes(Number(proxySend)) || ![0, 1].includes(Number(proxyAccept))) return fail("Proxy 模式无效");
    const trusted = row.proxyTrustedCidrs ?? (Array.isArray(row.proxy_trusted_cidrs) ? row.proxy_trusted_cidrs.join(",") : "");
    if (typeof trusted !== "string") return fail("可信 CIDR 格式无效");
    if (proxyAccept && !trusted.trim()) return fail("接收 Proxy 时必须填写可信来源 CIDR");
    const balances: Record<string, string> = { random: "rand", round_robin: "round", least_connections: "fifo" };
    if (row.balance === "least_connections") return fail("最少连接策略尚未支持导入");
    const strategy = row.strategy ?? balances[String(row.balance)] ?? "fifo";
    if (!["fifo", "rand", "round", "hash"].includes(String(strategy))) return fail("负载策略无效");
    return {
      name: row.name.trim(), tunnelId, remoteAddr: remoteAddr.trim(),
      inPort: Number(port) || null, protocol: protocol as TransferRule["protocol"],
      strategy: String(strategy), groupId: Number(row.groupId ?? 0),
      proxyAccept: Number(proxyAccept), proxySend: Number(proxySend), proxyTrustedCidrs: trusted,
    };
  });
}
