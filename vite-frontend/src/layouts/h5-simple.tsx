import React from 'react';
import { useLocation } from 'react-router-dom';

import { PanelHeader } from '@/components/panel-header';

export default function H5SimpleLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const location = useLocation();
  
  // 路由切换时回到顶部，避免上一页滚动位置保留
  React.useEffect(() => {
    try {
      window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
    } catch (e) {
      window.scrollTo(0, 0);
    }
    document.body.scrollTop = 0;
    document.documentElement.scrollTop = 0;
  }, [location.pathname]);


  return (
    <div className="flex flex-col min-h-screen bg-gray-100 dark:bg-black">
      {/* 顶部导航栏 */}
      <PanelHeader />

      {/* 主内容区域 */}
      <main className="flex-1 bg-gray-100 dark:bg-black pb-0">
        {children}
      </main>
    </div>
  );
}
