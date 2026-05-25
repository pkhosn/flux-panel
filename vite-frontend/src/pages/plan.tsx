import { useEffect, useState } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Table, TableHeader, TableColumn, TableBody, TableRow, TableCell } from "@heroui/table";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure } from "@heroui/modal";
import toast from "react-hot-toast";
import { createPlan, deletePlan, getPlanList, updatePlan } from "@/api";

interface PlanItem {
  id?: number;
  name: string;
  description?: string;
  price: number;
  flowGb: number;
  forwardNum: number;
  durationDays: number;
  stock: number;
  status: number;
}

const defaultForm: PlanItem = {
  name: "",
  description: "",
  price: 0,
  flowGb: 0,
  forwardNum: 0,
  durationDays: 0,
  stock: 0,
  status: 1,
};

export default function PlanPage() {
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [plans, setPlans] = useState<PlanItem[]>([]);
  const [form, setForm] = useState<PlanItem>(defaultForm);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [statusFilter, setStatusFilter] = useState<string>("all");
  const { isOpen, onOpen, onOpenChange } = useDisclosure();

  const loadPlans = async () => {
    setLoading(true);
    try {
      const res = await getPlanList();
      if (res.code === 0) {
        setPlans(res.data || []);
      } else {
        toast.error(res.msg || "获取套餐失败");
      }
    } catch (_e) {
      toast.error("获取套餐失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadPlans();
  }, []);

  const filteredPlans = plans.filter((item) => {
    if (statusFilter !== "all" && String(item.status) !== statusFilter) {
      return false;
    }
    if (!searchKeyword.trim()) {
      return true;
    }
    const key = searchKeyword.trim().toLowerCase();
    return (
      (item.name || "").toLowerCase().includes(key) ||
      (item.description || "").toLowerCase().includes(key)
    );
  });

  const openCreate = () => {
    setEditingId(null);
    setForm(defaultForm);
    onOpen();
  };

  const openEdit = (item: PlanItem) => {
    setEditingId(item.id || null);
    setForm({
      id: item.id,
      name: item.name || "",
      description: item.description || "",
      price: Number(item.price || 0),
      flowGb: Number(item.flowGb || 0),
      forwardNum: Number(item.forwardNum || 0),
      durationDays: Number(item.durationDays || 0),
      stock: Number(item.stock || 0),
      status: Number(item.status ?? 1),
    });
    onOpen();
  };

  const submit = async () => {
    if (!form.name.trim()) {
      toast.error("套餐名称不能为空");
      return;
    }
    setSaving(true);
    try {
      const payload = {
        ...form,
        name: form.name.trim(),
      };
      const res = editingId ? await updatePlan({ ...payload, id: editingId }) : await createPlan(payload);
      if (res.code === 0) {
        toast.success(editingId ? "更新成功" : "创建成功");
        onOpenChange();
        loadPlans();
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
    if (!window.confirm("确认删除该套餐？")) return;
    const res = await deletePlan(id);
    if (res.code === 0) {
      toast.success("已删除");
      loadPlans();
    } else {
      toast.error(res.msg || "删除失败");
    }
  };

  const toggleStatus = async (item: PlanItem) => {
    if (!item.id) return;
    const res = await updatePlan({ id: item.id, status: item.status === 1 ? 0 : 1 });
    if (res.code === 0) {
      toast.success(item.status === 1 ? "已停用" : "已启用");
      loadPlans();
    } else {
      toast.error(res.msg || "状态更新失败");
    }
  };

  return (
    <div className="p-4 md:p-6 space-y-4">
      <Card>
        <CardHeader className="flex justify-between">
          <span className="text-lg font-semibold">套餐管理</span>
          <div className="flex gap-2">
            <Button variant="flat" onPress={loadPlans} isLoading={loading}>
              刷新
            </Button>
            <Button color="primary" onPress={openCreate}>
              新增套餐
            </Button>
          </div>
        </CardHeader>
        <CardBody>
          <div className="grid grid-cols-1 md:grid-cols-[1fr_180px_auto] gap-2 mb-4">
            <Input
              placeholder="搜索套餐名/描述"
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
            <Button variant="flat" onPress={() => { setSearchKeyword(""); setStatusFilter("all"); }}>
              重置筛选
            </Button>
          </div>
          <Table aria-label="套餐列表" isStriped>
            <TableHeader>
              <TableColumn>ID</TableColumn>
              <TableColumn>名称</TableColumn>
              <TableColumn>价格</TableColumn>
              <TableColumn>流量(GB)</TableColumn>
              <TableColumn>转发数</TableColumn>
              <TableColumn>时长(天)</TableColumn>
              <TableColumn>库存</TableColumn>
              <TableColumn>状态</TableColumn>
              <TableColumn>操作</TableColumn>
            </TableHeader>
            <TableBody isLoading={loading} emptyContent="暂无套餐">
              {filteredPlans.map((item) => (
                <TableRow key={item.id}>
                  <TableCell>{item.id}</TableCell>
                  <TableCell>{item.name}</TableCell>
                  <TableCell>{item.price}</TableCell>
                  <TableCell>{item.flowGb}</TableCell>
                  <TableCell>{item.forwardNum}</TableCell>
                  <TableCell>{item.durationDays}</TableCell>
                  <TableCell>{item.stock}</TableCell>
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
              <ModalHeader>{editingId ? "编辑套餐" : "新增套餐"}</ModalHeader>
              <ModalBody className="space-y-3">
                <Input label="套餐名称" value={form.name} onValueChange={(v) => setForm((p) => ({ ...p, name: v }))} />
                <Input label="描述" value={form.description || ""} onValueChange={(v) => setForm((p) => ({ ...p, description: v }))} />
                <Input type="number" label="价格" value={String(form.price)} onValueChange={(v) => setForm((p) => ({ ...p, price: Number(v || 0) }))} />
                <Input type="number" label="流量(GB)" value={String(form.flowGb)} onValueChange={(v) => setForm((p) => ({ ...p, flowGb: Number(v || 0) }))} />
                <Input type="number" label="转发数" value={String(form.forwardNum)} onValueChange={(v) => setForm((p) => ({ ...p, forwardNum: Number(v || 0) }))} />
                <Input type="number" label="时长(天)" value={String(form.durationDays)} onValueChange={(v) => setForm((p) => ({ ...p, durationDays: Number(v || 0) }))} />
                <Input type="number" label="库存(-1不限)" value={String(form.stock)} onValueChange={(v) => setForm((p) => ({ ...p, stock: Number(v || 0) }))} />
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
