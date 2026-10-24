import * as vscode from 'vscode';
import { getConfig } from './config';
import type { EditorContext } from './tm/types';
import type { ImeAnalyzer } from './tm/analyzer';
import { currentMode, lastPluginSwitchAt, switchTo, type ImeMode } from './ime/imm32';
import { modeFromChar } from './char-mode';
import type { StatusBarController } from './status';

const DEBOUNCE_MS = 20;
const SUPPORTED_SCHEMES = new Set(['file', 'untitled', 'git', 'vscode-scm', 'vscode-remote']);

export function registerListeners(
  context: vscode.ExtensionContext,
  analyzer: ImeAnalyzer,
  status: StatusBarController,
) {
  const timers = new WeakMap<vscode.TextEditor, NodeJS.Timeout>();
  const busy = new WeakSet<vscode.TextEditor>();
  const lastContext = new WeakMap<vscode.TextEditor, EditorContext>();
  let lastNotifyAt = 0;

  // 失败冷却：切换失败后短时间内不重试同一目标，避免反复模拟热键把输入法来回振荡
  let lastFailedTarget: ImeMode | null = null;
  let lastFailedAt = 0;
  let consecutiveFailures = 0;

  // 手动切换覆盖：检测到用户手动切换后，同语境 60 秒内不强制切换（跟手机制）
  let overrideMode: ImeMode | null = null;
  let overrideAt = 0;
  let overrideCtx: EditorContext | null = null;
  let lastEvaluatedCtx: EditorContext | null = null;

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

      // 语境跟踪必须始终更新：即使当前语境"不干预"（如默认的字符串）。
      // 否则从字符串移回代码时会被"同语境不动作"误拦，手动切成的中文会挂到代码区。
      const prevCtx = lastContext.get(editor) ?? null;
      const sameCtx = ctx === prevCtx;
      lastContext.set(editor, ctx);
      lastEvaluatedCtx = ctx;

      const target: ImeMode | null =
        ctx === 'comment'
          ? 'chinese'
          : ctx === 'string'
            ? cfg.stringAsChinese
              ? 'chinese'
              : offset > 0
                ? modeFromChar(doc.getText(new vscode.Range(doc.positionAt(offset - 1), doc.positionAt(offset))))
                : null
            : ctx === 'code'
              ? 'english'
              : null;
      if (!target) return;
      if (sameCtx) return;

      // 手动切换覆盖：有效期内同语境不与用户抢输入法
      if (cfg.respectManualToggles && overrideMode && overrideMode !== target &&
          Date.now() - overrideAt < 60000 && (overrideCtx === null || overrideCtx === ctx)) {
        return;
      }

      // 失败冷却：短时间内不对同一目标反复尝试
      if (target === lastFailedTarget && Date.now() - lastFailedAt < 1500) return;

      const result = await switchTo(target, cfg.toggleHotkey, cfg.useDirectImmSet);
      status.update(currentMode());if (result === 'failed') {
        consecutiveFailures++;
        lastFailedTarget = target;
        lastFailedAt = Date.now();
        if (cfg.showFailureNotifications && consecutiveFailures >= 3 && Date.now() - lastNotifyAt > 10000) {
          lastNotifyAt = Date.now();
          void vscode.window.showWarningMessage(
            `CodeIME：连续 ${consecutiveFailures} 次无法自动切换到${target === 'chinese' ? '中文' : '英文'}。` +
            '请检查设置中的切换热键是否与系统输入法一致（微软拼音默认 Shift）；' +
            '若使用微软拼音，建议关闭「优先直接设置输入法状态」。',
          );
        }
      } else {
        consecutiveFailures = 0;
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

  // 模式轮询（250ms）：把输入法模式变化归因给插件或用户，用户变化记录为手动覆盖
  let lastKnownMode: ImeMode | null = null;
  let pollerInitialized = false;
  const modeTimer = setInterval(() => {
    if (!vscode.window.state.focused) return;
    const m = currentMode();
    if (!m) return;
    if (!pollerInitialized) {
      pollerInitialized = true;
      lastKnownMode = m;
      return;
    }
    if (m === lastKnownMode) return;
    const sincePlugin = Date.now() - lastPluginSwitchAt();
    lastKnownMode = m;
    if (sincePlugin < 0 || sincePlugin > 2500) {
      overrideMode = m;
      overrideAt = Date.now();
      overrideCtx = lastEvaluatedCtx;
      status.update(m);
    }
  }, 250);
  context.subscriptions.push({ dispose: () => clearInterval(modeTimer) });

  status.update(currentMode());
}
