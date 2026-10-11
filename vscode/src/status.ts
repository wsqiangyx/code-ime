import * as vscode from 'vscode';
import type { ImeMode } from './ime/imm32';

/** 状态栏显示当前输入法模式，点击切换启用/禁用 */
export class StatusBarController {
  private item: vscode.StatusBarItem;

  constructor(private immAvailable: boolean) {
    this.item = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 90);
    this.item.name = 'CodeIME';
    this.item.command = 'codeIME.toggleEnabled';
    this.item.show();
  }

  update(mode: ImeMode | null) {
    if (!this.immAvailable) {
      this.item.text = 'IME N/A';
      this.item.tooltip = 'CodeIME：当前平台不支持输入法切换（仅支持 Windows）';
      return;
    }
    this.item.text = mode === 'chinese' ? '$(symbol-string) 中' : mode === 'english' ? 'A 英' : 'IME';
    this.item.tooltip = 'CodeIME：当前输入法模式（点击启用/禁用自动切换）';
  }

  dispose() {
    this.item.dispose();
  }
}
