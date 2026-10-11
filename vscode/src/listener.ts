import * as vscode from 'vscode';
import { getConfig } from './config';
import type { EditorContext } from './tm/types';
import type { ImeAnalyzer } from './tm/analyzer';
import { currentMode, switchTo, type ImeMode } from './ime/imm32';
import type { StatusBarController } from './status';

const DEBOUNCE_MS = 50;
const SUPPORTED_SCHEMES = new Set(['file', 'untitled', 'git', 'vscode-scm']);

export function registerListeners(
  context: vscode.ExtensionContext,
  analyzer: ImeAnalyzer,
  status: StatusBarController,
) {
  const timers = new WeakMap<vscode.TextEditor, NodeJS.Timeout>();
  const busy = new WeakSet<vscode.TextEditor>();
  const lastContext = new WeakMap<vscode.TextEditor, EditorContext>();
  let lastNotifyAt = 0;

  const isSupported = (editor: vscode.TextEditor) =>
    SUPPORTED_SCHEMES.has(editor.document.uri.scheme);

  const schedule = (editor: vscode.TextEditor, delay = DEBOUNCE_MS) => {
    const old = timers.get(editor);
    if (old) clearTimeout(old);
    const t = setTimeout(() => {
      timers.delete(editor);
      void evaluate(editor);
    }, delay);
    timers.set(editor, t);
  };

  const evaluate = async (editor: vscode.TextEditor) => {
    if (busy.has(editor)) {
      schedule(editor); // 上一次还没跑完，稍后重试
      return;
    }
    busy.add(editor);
    try {
      const cfg = getConfig();
      if (!cfg.enabled) return;
      if (!vscode.window.state.focused) return;
      if (editor !== vscode.window.activeTextEditor) return;
      if (editor.selections.some(s => !s.isEmpty)) return;

      const doc = editor.document;
      const offset = doc.offsetAt(editor.selection.active);

      // SCM 提交输入框整体按注释（中文）处理，等同 IDEA 版的 COMMIT_EDITMSG
      let ctx: EditorContext;
      if (doc.uri.scheme === 'vscode-scm') {
        ctx = 'comment';
      } else {
        ctx = await analyzer.classify(doc.languageId, doc, offset);
      }

      if (ctx === lastContext.get(editor)) return; // 同一上下文内移动，不动作
      lastContext.set(editor, ctx);

      const target: ImeMode | null =
        ctx === 'comment'
          ? 'chinese'
          : ctx === 'string'
            ? cfg.stringAsChinese
              ? 'chinese'
              : null
            : ctx === 'code'
              ? 'english'
              : null;
      if (!target) return;

      const result = await switchTo(target, cfg.toggleHotkey, cfg.useDirectImmSet);
      status.update(currentMode());
      if (result === 'failed' && cfg.showFailureNotifications) {
        const now = Date.now();
        if (now - lastNotifyAt > 3000) {
          lastNotifyAt = now;
          void vscode.window.showWarningMessage(
            'CodeIME：无法自动切换到' + (target === 'chinese' ? '中文' : '英文') +
            '。请确认设置中的切换热键与系统输入法一致（微软拼音默认 Shift）。',
          );
        }
      }
    } finally {
      busy.delete(editor);
    }
  };

  const resync = () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || !isSupported(editor)) return;
    lastContext.delete(editor);
    schedule(editor, 180); // 规避 Alt+Tab 期间切换不稳
  };

  context.subscriptions.push(
    vscode.window.onDidChangeTextEditorSelection(e => {
      if (!isSupported(e.textEditor)) return;
      schedule(e.textEditor);
    }),
    vscode.workspace.onDidChangeTextDocument(e => {
      let firstLine: number | null = null;
      for (const ch of e.contentChanges) {
        const line = e.document.positionAt(ch.rangeOffset).line;
        firstLine = firstLine === null ? line : Math.min(firstLine, line);
      }
      analyzer.invalidate(e.document, firstLine);
    }),
    vscode.window.onDidChangeWindowState(s => {
      if (s.focused) {
        resync();
        status.update(currentMode());
      } else {
        const cfg = getConfig();
        if (cfg.onDeactivate !== 'none') {
          void switchTo(
            cfg.onDeactivate === 'chinese' ? 'chinese' : 'english',
            cfg.toggleHotkey,
            cfg.useDirectImmSet,
          );
        }
      }
    }),
    vscode.window.onDidChangeActiveTextEditor(e => {
      if (e && isSupported(e)) resync();
    }),
  );

  status.update(currentMode());
}
