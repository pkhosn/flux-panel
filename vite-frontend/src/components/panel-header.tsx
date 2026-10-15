import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { Dropdown, DropdownTrigger, DropdownMenu, DropdownItem } from '@heroui/dropdown';
import { Modal, ModalContent, ModalHeader, ModalBody } from '@heroui/modal';
import { useTheme } from '@heroui/use-theme';
import { Logo } from '@/components/icons';
import { siteConfig } from '@/config/site';
import { getConfigByName, getConfigs } from '@/api';
import { applyTheme, saveTheme } from '@/utils/theme';
import { safeLogout } from '@/utils/logout';
import './panel-header.css';

export function PanelHeader({ onToggleSidebar, sidebarExpanded = true, onPassword }: {
  onToggleSidebar?: () => void; sidebarExpanded?: boolean; onPassword?: () => void;
}) {
  const navigate = useNavigate();
  const { theme, setTheme } = useTheme();
  const [english, setEnglish] = useState(localStorage.getItem('panel_locale') === 'en-US');
  const [brand, setBrand] = useState({ name: siteConfig.name, logo: localStorage.getItem('vite_config_app_logo') || '' });
  const [announcement, setAnnouncement] = useState({ title: '', content: '' });
  const [noticeOpen, setNoticeOpen] = useState(false);
  const [noticeLoading, setNoticeLoading] = useState(false);
  const [noticeError, setNoticeError] = useState(false);
  const text = (zh: string, en: string) => english ? en : zh;
  useEffect(() => {
    let active = true;
    const load = async () => {
      const results = await Promise.all(['app_name', 'app_logo'].map(name => getConfigByName(name).catch(() => null)));
      if (!active) return;
      setBrand({ name: results[0]?.data?.value || siteConfig.name, logo: results[1]?.data?.value || '' });
    };
    void load();
    window.addEventListener('configUpdated', load);
    return () => { active = false; window.removeEventListener('configUpdated', load); };
  }, []);
  const showAnnouncement = async () => {
    setNoticeOpen(true); setNoticeLoading(true); setNoticeError(false);
    try {
      const result = await getConfigs();
      if (result.code !== 0) throw Error('announcement');
      setAnnouncement({ title: result.data?.announcement_title || '', content: result.data?.announcement_content || '' });
    } catch { setNoticeError(true); }
    finally { setNoticeLoading(false); }
  };
  const icon = (children: ReactNode) => <svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">{children}</svg>;
  return <>
    <header className="panel-header" aria-label={text('顶部导航', 'Top navigation')}>
      <div className="panel-header-brand">
        <a href="/dashboard" className="panel-header-brand-link" aria-label={brand.name}>
          {brand.logo ? <img src={brand.logo} alt="" className="panel-header-logo" onError={() => setBrand(value => ({ ...value, logo: '' }))} /> : <Logo size={26} className="panel-header-logo" />}
          <span>{brand.name}</span>
        </a>
        {onToggleSidebar && <button className="panel-header-collapse" onClick={onToggleSidebar} aria-expanded={sidebarExpanded} aria-label={text('切换侧边栏', 'Toggle sidebar')}>
          {icon(<><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16m7-12-4 4 4 4" /></>)}
        </button>}
      </div>
      <div className="panel-header-tools">
        <div className="panel-header-buttons">
          <button className="panel-header-tool" onClick={showAnnouncement} title={text('公告', 'Announcements')} aria-label={text('公告', 'Announcements')}>{icon(<><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></>)}</button>
          <Dropdown placement="bottom-end"><DropdownTrigger><button className="panel-header-tool" title={text('语言切换', 'Language')} aria-label={text('语言切换', 'Language')}>{icon(<><path d="M3 5h12M9 3v2m4 0c-1 6-4 9-10 12m2-9c1 4 4 6 8 8m1 5 4-11 4 11m-6-4h6" /></>)}</button></DropdownTrigger>
            <DropdownMenu aria-label="Language" selectionMode="single" selectedKeys={[english ? 'en-US' : 'zh-CN']} onAction={key => { const locale = String(key); setEnglish(locale === 'en-US'); localStorage.setItem('panel_locale', locale); window.dispatchEvent(new Event('panelLocaleChanged')); }}>
              <DropdownItem key="zh-CN">简体中文</DropdownItem><DropdownItem key="en-US">English</DropdownItem>
            </DropdownMenu>
          </Dropdown>
          <button className="panel-header-tool" aria-label={text('切换主题', 'Toggle theme')} aria-pressed={theme === 'dark'} title={text('切换主题', 'Toggle theme')} onClick={() => { const next = theme === 'dark' ? 'light' : 'dark'; saveTheme(next); applyTheme(next); setTheme(next); }}>{icon(<><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" /></>)}</button>
        </div>
        <Dropdown placement="bottom-end"><DropdownTrigger><button className="panel-header-avatar" aria-label={text('用户菜单', 'User menu')}><span>D</span><span className="panel-header-vip">AVIP</span></button></DropdownTrigger>
          <DropdownMenu aria-label={text('用户菜单', 'User menu')}>
            <DropdownItem key="profile" onPress={() => navigate('/profile')}>{text('个人中心', 'Profile')}</DropdownItem>
            <DropdownItem key="password" onPress={() => onPassword ? onPassword() : navigate('/profile')}>{text('修改密码', 'Change password')}</DropdownItem>
            <DropdownItem key="logout" color="danger" onPress={() => { safeLogout(); navigate('/'); }}>{text('退出登录', 'Sign out')}</DropdownItem>
          </DropdownMenu>
        </Dropdown>
      </div>
    </header>
    <Modal isOpen={noticeOpen} onOpenChange={setNoticeOpen} scrollBehavior="inside"><ModalContent><ModalHeader>{announcement.title || text('公告', 'Announcements')}</ModalHeader><ModalBody className="pb-6 whitespace-pre-wrap break-words">{noticeLoading ? text('加载中…', 'Loading…') : noticeError ? text('公告加载失败，请关闭后重试', 'Could not load announcements. Please try again.') : announcement.content || text('暂无公告', 'No announcements')}</ModalBody></ModalContent></Modal>
  </>;
}
