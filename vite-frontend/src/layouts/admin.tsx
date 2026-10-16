import { isAdminWorkspace } from '@/utils/workspace';
import { translate } from '@/utils/locale';
import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Button } from "@heroui/button";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure } from "@heroui/modal";
import { Input } from "@heroui/input";
import { toast } from 'react-hot-toast';

import { PanelHeader } from '@/components/panel-header';
import { updatePassword } from '@/api';
import { safeLogout } from '@/utils/logout';

interface MenuItem {
  path: string;
  label: string;
  icon: React.ReactNode;
  adminOnly?: boolean;
}

interface PasswordForm {
  newUsername: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const { isOpen, onOpen, onOpenChange } = useDisclosure();

  const [isMobile, setIsMobile] = useState(false);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(false);
  const [sidebarHovered, setSidebarHovered] = useState(false);
  const sidebarLeaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const enterSidebar = () => {
    if (sidebarLeaveTimer.current) clearTimeout(sidebarLeaveTimer.current);
    setSidebarHovered(true);
  };
  const leaveSidebar = () => {
    if (sidebarLeaveTimer.current) clearTimeout(sidebarLeaveTimer.current);
    sidebarLeaveTimer.current = setTimeout(() => setSidebarHovered(false), 120);
  };
  useEffect(() => () => {
    if (sidebarLeaveTimer.current) clearTimeout(sidebarLeaveTimer.current);
  }, []);
  const sidebarCompact = isSidebarCollapsed && !sidebarHovered;
  const [mobileMenuVisible, setMobileMenuVisible] = useState(false);
  const [isAdmin, setIsAdmin] = useState(false);
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [passwordForm, setPasswordForm] = useState<PasswordForm>({
    newUsername: '',
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  });

  // 菜单项配置
  const menuItems: MenuItem[] = [
    {
      path: '/dashboard',
      label: translate("仪表板"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
        </svg>
      )
    },
    {
      path: '/forward',
      label: translate("我的转发"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clipRule="evenodd" />
        </svg>
      )
    },
    {
      path: '/billing',
      label: translate(isAdmin ? "订单管理" : "购买充值"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M4 4a2 2 0 00-2 2v1h16V6a2 2 0 00-2-2H4z" />
          <path fillRule="evenodd" d="M18 9H2v5a2 2 0 002 2h12a2 2 0 002-2V9zM8 13a1 1 0 000 2h4a1 1 0 100-2H8z" clipRule="evenodd" />
        </svg>
      )
    },
    {
      path: '/tunnel',
      label: translate("隧道管理"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z" clipRule="evenodd" />
        </svg>
      )
    },
    {
      path: '/node',
      label: translate("节点监控"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M3 3a1 1 0 000 2v8a2 2 0 002 2h2.586l-1.293 1.293a1 1 0 101.414 1.414L10 15.414l2.293 2.293a1 1 0 001.414-1.414L12.414 15H15a2 2 0 002-2V5a1 1 0 100-2H3zm11.707 4.707a1 1 0 00-1.414-1.414L10 9.586 8.707 8.293a1 1 0 00-1.414 0l-2 2a1 1 0 101.414 1.414L8 10.414l1.293 1.293a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
        </svg>
      )
    },
    {
      path: '/limit',
      label: translate("限速管理"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/user',
      label: translate("用户管理"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zM17 6a3 3 0 11-6 0 3 3 0 016 0zM12.93 17c.046-.327.07-.66.07-1a6.97 6.97 0 00-1.5-4.33A5 5 0 0119 16v1h-6.07zM6 11a5 5 0 015 5v1H1v-1a5 5 0 015-5z" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/plan',
      label: translate("套餐管理"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M4 3a1 1 0 00-1 1v3h14V4a1 1 0 00-1-1H4zM3 9v7a1 1 0 001 1h12a1 1 0 001-1V9H3z" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/redeem',
      label: translate("兑换码管理"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M3 5a2 2 0 012-2h2.586a2 2 0 001.414-.586l.414-.414a2 2 0 012.828 0l.414.414A2 2 0 0014.071 3H15a2 2 0 012 2v2.586a2 2 0 00.586 1.414l.414.414a2 2 0 010 2.828l-.414.414A2 2 0 0017 14.071V15a2 2 0 01-2 2h-.929a2 2 0 00-1.415.586l-.414.414a2 2 0 01-2.828 0l-.414-.414A2 2 0 007.586 17H5a2 2 0 01-2-2v-2.929a2 2 0 00-.586-1.415l-.414-.414a2 2 0 010-2.828l.414-.414A2 2 0 003 6.586V5z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/config',
      label: translate("网站配置"),
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    }
  ];

  // 检查移动端
  const checkMobile = () => {
    setIsMobile(window.innerWidth <= 768);
    if (window.innerWidth > 768) {
      setMobileMenuVisible(false);
    }
  };

  useEffect(() => {
    // 获取用户信息


    setIsAdmin(isAdminWorkspace());
    setIsSidebarCollapsed(localStorage.getItem('admin_sidebar_collapsed') === 'true');

    // 响应式检查
    checkMobile();
    window.addEventListener('resize', checkMobile);

    return () => {
      window.removeEventListener('resize', checkMobile);
    };
  }, []);

  // 退出登录
  const handleLogout = () => {
    safeLogout();
    navigate('/');
  };

  // 切换移动端菜单
  const toggleMobileMenu = () => {
    setMobileMenuVisible(!mobileMenuVisible);
  };

  const toggleSidebar = () => {
    const nextCollapsed = !isSidebarCollapsed;
    setIsSidebarCollapsed(nextCollapsed);
    setSidebarHovered(false);
    localStorage.setItem('admin_sidebar_collapsed', nextCollapsed.toString());
  };

  // 隐藏移动端菜单
  const hideMobileMenu = () => {
    setMobileMenuVisible(false);
  };

  // 菜单点击处理
  const handleMenuClick = (path: string) => {
    navigate(path);
    if (isMobile) {
      hideMobileMenu();
    }
  };

  // 密码表单验证
  const validatePasswordForm = (): boolean => {
    if (!passwordForm.newUsername.trim()) {
      toast.error(translate("请输入新用户名"));
      return false;
    }
    if (passwordForm.newUsername.length < 3) {
      toast.error(translate("用户名长度至少3位"));
      return false;
    }
    if (!passwordForm.currentPassword) {
      toast.error(translate("请输入当前密码"));
      return false;
    }
    if (!passwordForm.newPassword) {
      toast.error(translate("请输入新密码"));
      return false;
    }
    if (passwordForm.newPassword.length < 6) {
      toast.error(translate("新密码长度不能少于6位"));
      return false;
    }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      toast.error(translate("两次输入密码不一致"));
      return false;
    }
    return true;
  };

  // 提交密码修改
  const handlePasswordSubmit = async () => {
    if (!validatePasswordForm()) return;

    setPasswordLoading(true);
    try {
      const response = await updatePassword(passwordForm);
      if (response.code === 0) {
        toast.success(translate("密码修改成功，请重新登录"));
        onOpenChange();
        handleLogout();
      } else {
        toast.error(response.msg || '密码修改失败');
      }
    } catch (error) {
      toast.error(translate("修改密码时发生错误"));
      console.error('修改密码错误:', error);
    } finally {
      setPasswordLoading(false);
    }
  };

  // 重置密码表单
  const resetPasswordForm = () => {
    setPasswordForm({
      newUsername: '',
      currentPassword: '',
      newPassword: '',
      confirmPassword: ''
    });
  };

  // 过滤菜单项（根据权限）
  const filteredMenuItems = menuItems.filter(item =>
    !item.adminOnly || isAdmin
  );

  return (
          <div className={`flex flex-col ${isMobile ? 'min-h-screen' : 'h-screen'} bg-gray-100 dark:bg-black`}>
      <PanelHeader onToggleSidebar={isMobile ? toggleMobileMenu : toggleSidebar} sidebarExpanded={isMobile ? mobileMenuVisible : !sidebarCompact} sidebarCompact={!isMobile && sidebarCompact} onBrandEnter={enterSidebar} onBrandLeave={leaveSidebar} onPassword={onOpen} />
      <div className="flex flex-1 min-h-0">
      {/* 移动端遮罩层 */}
      {isMobile && mobileMenuVisible && (
        <div
          className="fixed inset-0 backdrop-blur-sm bg-white/50 dark:bg-black/30 z-40"
          onClick={hideMobileMenu}
        />
      )}

      {/* 左侧菜单栏 */}
      <aside onMouseEnter={enterSidebar} onMouseLeave={leaveSidebar} className={`
        ${isMobile ? 'fixed' : 'relative'}
        ${isMobile && !mobileMenuVisible ? '-translate-x-full' : 'translate-x-0'}
        ${isMobile ? 'w-64' : sidebarCompact ? 'w-20' : 'w-[240px]'}
        bg-white dark:bg-black
        shadow-lg
        border-r border-gray-200 dark:border-gray-600
        z-50
        shrink-0 overflow-hidden transition-[width,transform] duration-150 ease-out
        flex flex-col
        ${isMobile ? 'h-[calc(100dvh-56px)]' : 'h-full'}
        ${isMobile ? 'top-14 left-0' : ''}
      `}>
                 {/* 菜单导航 */}
         <nav className="flex-1 px-4 py-6 overflow-y-auto overflow-x-hidden">
           <ul className="space-y-1">
            {filteredMenuItems.map((item) => {
              const isActive = location.pathname === item.path;
              return (
                <li key={item.path}>
                                     <button
                     onClick={() => handleMenuClick(item.path)}
                     className={`
                       w-full flex items-center px-4 py-3 rounded-lg text-left
                       transition-colors duration-200 min-h-[44px]
                       ${!isMobile && sidebarCompact ? 'justify-center px-0' : 'gap-3'}
                       ${isActive
                         ? 'bg-primary-100 dark:bg-primary-600/20 text-primary-600 dark:text-primary-300'
                         : 'text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-900'
                       }
                     `}
                     title={!isMobile && sidebarCompact ? item.label : undefined}
                   >
                     <div className="flex-shrink-0">
                       {item.icon}
                     </div>
                     <span className={`font-medium text-sm whitespace-nowrap ${!isMobile && sidebarCompact ? 'hidden' : ''}`}>{item.label}</span>
                   </button>
                </li>
              );
            })}
          </ul>
        </nav>

                {/* 底部版权信息 */}
        <div className={`px-4 py-2 pb-4 mt-auto flex-shrink-0 whitespace-nowrap ${!isMobile && sidebarCompact ? 'invisible' : ''}`}>
          <div className="text-center">
            <p className="text-xs text-gray-400 dark:text-gray-500">
              Powered by{' '}
              <a
                href="https://github.com/bqlpfy/flux-panel"
                target="_blank"
                rel="noopener noreferrer"
                className="text-gray-500 dark:text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors"
              >
                flux-panel
              </a>
            </p>
          </div>
        </div>
      </aside>

      {/* 主内容区域 */}
      <div className={`flex flex-col flex-1 min-w-0 ${isMobile ? 'min-h-0' : 'h-full overflow-hidden'}`}>
        {/* 主内容 */}
        <main className={`flex-1 bg-gray-100 dark:bg-black ${isMobile ? '' : 'overflow-y-auto'}`}>
          {children}
        </main>
      </div>

      </div>
      {/* 修改密码弹窗 */}
      <Modal
        isOpen={isOpen}
        onOpenChange={() => {
          onOpenChange();
          resetPasswordForm();
        }}
        size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
      >
                 <ModalContent>
           {(onClose: () => void) => (
            <>
              <ModalHeader className="flex flex-col gap-1">{translate("修改密码")}</ModalHeader>
              <ModalBody>
                                 <div className="space-y-4">
                   <Input
                     label={translate("新用户名")}
                     placeholder={translate("请输入新用户名（至少3位）")}
                     value={passwordForm.newUsername}
                     onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, newUsername: e.target.value }))}
                     variant="bordered"
                   />
                   <Input
                     label={translate("当前密码")}
                     type="password"
                     placeholder={translate("请输入当前密码")}
                     value={passwordForm.currentPassword}
                     onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, currentPassword: e.target.value }))}
                     variant="bordered"
                   />
                   <Input
                     label={translate("新密码")}
                     type="password"
                     placeholder={translate("请输入新密码（至少6位）")}
                     value={passwordForm.newPassword}
                     onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, newPassword: e.target.value }))}
                     variant="bordered"
                   />
                   <Input
                     label={translate("确认密码")}
                     type="password"
                     placeholder={translate("请再次输入新密码")}
                     value={passwordForm.confirmPassword}
                     onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, confirmPassword: e.target.value }))}
                     variant="bordered"
                   />
                 </div>
              </ModalBody>
              <ModalFooter>
                <Button color="default" variant="light" onPress={onClose}>{translate("取消")}</Button>
                <Button
                  color="primary"
                  onPress={handlePasswordSubmit}
                  isLoading={passwordLoading}
                >{translate("确定")}</Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}
