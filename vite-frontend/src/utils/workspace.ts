import { isAdmin } from './auth';

/** UI preference only; API permissions always come from the authenticated token. */
export function isAdminWorkspace(): boolean {
  return isAdmin() && localStorage.getItem('panel_workspace') !== 'user';
}
