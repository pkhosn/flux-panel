import DdnsSettings from '@/components/ddns-settings';
import { translate } from '@/utils/locale';

export default function DdnsPage() {
  return <div className="px-3 py-5 lg:px-6 w-full pb-8">
    <header className="mb-4">
      <h1 className="text-2xl font-semibold tracking-tight">{translate('动态 DNS')}</h1>
      <p className="text-sm text-default-500 mt-1">管理自己的 Cloudflare 凭证与同步任务</p>
    </header>
    <DdnsSettings />
  </div>;
}
