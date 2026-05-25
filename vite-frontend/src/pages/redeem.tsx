import { useEffect, useState } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Table, TableHeader, TableColumn, TableBody, TableRow, TableCell } from "@heroui/table";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure } from "@heroui/modal";
import toast from "react-hot-toast";
import { createRedeemCode, deleteRedeemCode, getRedeemCodeList, updateRedeemCode } from "@/api";

interface RedeemItem {
  id?: number;
  code: string;
  flowGb: number;
  forwardNum: number;
  durationDays: number;
  used: number;
  status: number;
}

const defaultForm: RedeemItem = {
  code: "",
  flowGb: 0,
  forwardNum: 0,
  durationDays: 0,
  used: 0,
  status: 1,
};

export default function RedeemPage() {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [items, setItems] = useState<RedeemItem[]>([]);
  const [form, setForm] = useState<RedeemItem>(defaultForm);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("all");
  const [usedFilter, setUsedFilter] = useState<string>("all");
  const { isOpen, onOpen, onOpenChange } = useDisclosure();

  const load = async () => {
    setLoading(true);
    try {
      const res = await getRedeemCodeList();
      if (res.code === 0) {
        setItems(res.data || []);
      } else {
        toast.error(res.msg || "获取兑换码失败");
      }
    } catch (_e) {
      toast.error("获取兑换码失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const filteredItems = items.filter((item) => {
    if (statusFilter !== "all" && String(item.status) !== statusFilter) return false;
    if (usedFilter !== "all" && String(item.used) !== usedFilter) return false;
    if (!searchKeyword.trim()) return true;
    const key = searchKeyword.trim().toLowerCase();
    return (item.code || "").toLowerCase().includes(key);
  });

  const openCreate = () => {
    setEditingId(null);
    setForm(defaultForm);
    onOpen();
  };

  const openEdit = (item: RedeemItem) => {
    setEditingId(item.id || null);
    setForm({
      id: item.id,
      code: item.code || "",
      flowGb: Number(item.flowGb || 0),
      forwardNum: Number(item.forwardNum || 0),
      durationDays: Number(item.durationDays || 0),
      used: Number(item.used || 0),
      status: Number(item.status ?? 1),
    });
    onOpen();
  };

  const submit = async () => {
    if (!form.code.trim()) {
      toast.error("兑换码不能为空");
      return;
    }
    setSaving(true);
    try {
      const payload = {
        ...form,
        code: form.code.trim(),
      };
      const res = editingId ? await updateRedeemCode({ ...payload, id: editingId }) : await createRedeemCode(payload);
      if (res.code === 0) {
        toast.success(editingId ? "更新成功" : "创建成功");
        onOpenChange();
        load();
      } else {
        toast.error(res.msg || "保存失败");
      }
    } catch (_e) {
      toast.error("保存失败");
    } finally {
      setSaving(false);
    }
  };

  const remove = async (id?: number) => {
    if (!id) return;
    if (!window.confirm("确认删除该兑换码？")) return;
    const res = await deleteRedeemCode(id);
    if (res.code === 0) {
      toast.success("已删除");
      load();
    } else {
      toast.error(res.msg || "删除失败");
    }
  };

  const toggleStatus = async (item: RedeemItem) => {
    if (!item.id) return;
    const res = await updateRedeemCode({ id: item.id, status: item.status === 1 ? 0 : 1 });
    if (res.code === 0) {
      toast.success(item.status === 1 ? "已停用" : "已启用");
      load();
    } else {
      toast.error(res.msg || "状态更新失败");
    }
  };

  return (
    <div className="p-4 md:p-6 space-y-4">
      <Card>
        <CardHeader className="flex justify-between">
          <span className="text-lg font-semibold">兑换码管理</span>
          <div className="flex gap-2">
            <Button variant="flat" onPress={load} isLoading={loading}>
              刷新
            </Button>
            <Button color="primary" onPress={openCreate}>
              新增兑换码
            </Button>
          </div>
        </CardHeader>
        <CardBody>
          <div className="grid grid-cols-1 md:grid-cols-[1fr_180px_180px_auto] gap-2 mb-4">
            <Input
              placeholder="搜索兑换码"
              value={searchKeyword}
              onValueChange={setSearchKeyword}
            />
            <Select
              label="状态筛选"
              selectedKeys={[statusFilter]}
              onSelectionChange={(keys) => setStatusFilter(String(Array.from(keys)[0] || "all"))}
            >
              <SelectItem key="all">全部状态</SelectItem>
              <SelectItem key="1">启用</SelectItem>
              <SelectItem key="0">停用</SelectItem>
            </Select>
            <Select
              label="使用状态"
              selectedKeys={[usedFilter]}
              onSelectionChange={(keys) => setUsedFilter(String(Array.from(keys)[0] || "all"))}
            >
              <SelectItem key="all">全部</SelectItem>
              <SelectItem key="0">未使用</SelectItem>
              <SelectItem key="1">已使用</SelectItem>
            </Select>
            <Button variant="flat" onPress={() => { setSearchKeyword(""); setStatusFilter("all"); setUsedFilter("all"); }}>
              重置筛选
            </Button>
          </div>
          <Table aria-label="兑换码列表" isStriped>
            <TableHeader>
              <TableColumn>ID</TableColumn>
              <TableColumn>兑换码</TableColumn>
              <TableColumn>流量(GB)</TableColumn>
              <TableColumn>转发数</TableColumn>
              <TableColumn>时长(天)</TableColumn>
              <TableColumn>已使用</TableColumn>
              <TableColumn>状态</TableColumn>
              <TableColumn>操作</TableColumn>
            </TableHeader>
            <TableBody isLoading={loading} emptyContent="暂无兑换码">
              {filteredItems.map((item) => (
                <TableRow key={item.id}>
                  <TableCell>{item.id}</TableCell>
                  <TableCell>{item.code}</TableCell>
                  <TableCell>{item.flowGb}</TableCell>
                  <TableCell>{item.forwardNum}</TableCell>
                  <TableCell>{item.durationDays}</TableCell>
                  <TableCell>{item.used === 1 ? "是" : "否"}</TableCell>
                  <TableCell>{item.status === 1 ? "启用" : "停用"}</TableCell>
                  <TableCell>
                    <div className="flex gap-2">
                      <Button size="sm" variant="flat" onPress={() => openEdit(item)}>
                        编辑
                      </Button>
                      <Button size="sm" variant="flat" onPress={() => toggleStatus(item)}>
                        {item.status === 1 ? "停用" : "启用"}
                      </Button>
                      <Button size="sm" color="danger" variant="flat" onPress={() => remove(item.id)}>
                        删除
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardBody>
      </Card>

      <Modal isOpen={isOpen} onOpenChange={onOpenChange}>
        <ModalContent>
          {(close) => (
            <>
              <ModalHeader>{editingId ? "编辑兑换码" : "新增兑换码"}</ModalHeader>
              <ModalBody className="space-y-3">
                <Input label="兑换码" value={form.code} onValueChange={(v) => setForm((p) => ({ ...p, code: v }))} />
                <Input type="number" label="流量(GB)" value={String(form.flowGb)} onValueChange={(v) => setForm((p) => ({ ...p, flowGb: Number(v || 0) }))} />
                <Input type="number" label="转发数" value={String(form.forwardNum)} onValueChange={(v) => setForm((p) => ({ ...p, forwardNum: Number(v || 0) }))} />
                <Input type="number" label="时长(天)" value={String(form.durationDays)} onValueChange={(v) => setForm((p) => ({ ...p, durationDays: Number(v || 0) }))} />
                <Input type="number" label="状态(1启用/0停用)" value={String(form.status)} onValueChange={(v) => setForm((p) => ({ ...p, status: Number(v || 0) }))} />
              </ModalBody>
              <ModalFooter>
                <Button variant="flat" onPress={close}>
                  取消
                </Button>
                <Button color="primary" isLoading={saving} onPress={submit}>
                  保存
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}
