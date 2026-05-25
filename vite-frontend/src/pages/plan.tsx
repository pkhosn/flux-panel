import { Card, CardBody, CardHeader } from "@heroui/card";

export default function PlanPage() {
  return (
    <div className="p-4 md:p-6">
      <Card>
        <CardHeader className="text-lg font-semibold">套餐管理</CardHeader>
        <CardBody className="text-sm text-default-600">
          当前版本后端未开放套餐管理接口，页面功能暂不可用。
        </CardBody>
      </Card>
    </div>
  );
}

