import * as vscode from 'vscode';

export interface CodeImeConfig {
  enabled: boolean;
  stringAsChinese: boolean;
  useDirectImmSet: boolean;
  toggleHotkey: string;
  onDeactivate: string;
  showFailureNotifications: boolean;
}

export function getConfig(): CodeImeConfig {
  const c = vscode.workspace.getConfiguration('codeIME');
  return {
    enabled: c.get<boolean>('enabled', true),
    stringAsChinese: c.get<boolean>('stringAsChinese', false),
    useDirectImmSet: c.get<boolean>('useDirectImmSet', true),
    toggleHotkey: c.get<string>('toggleHotkey', 'shift'),
    onDeactivate: c.get<string>('onDeactivate', 'none'),
    showFailureNotifications: c.get<boolean>('showFailureNotifications', true),
  };
}
