import * as vscode from 'vscode';
import { getConfig } from './config';
import type { EditorContext } from './tm/types';
import type { ImeAnalyzer } from './tm/analyzer';
import { currentMode, assumedMode, syncAssumedMode, switchTo, type ImeMode } from './ime/imm32';
import { isCjkChar, isComposingText, modeFromChar } from './char-mode';
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
  // 覆盖发生时的语境（null = 对话框/搜索框里切的，短暂全局让位）
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

      // 只在常规文件编辑器切换。SCM 提交输入框按中文处理（等同 IDEA 版的 COMMIT_EDITMSG）；
      // 输出面板、搜索框等非常规文档不碰——用户意图明确，插件抢输入法只会帮倒忙
      let ctx: EditorContext;
      if (doc.uri.scheme === 'vscode-scm') {
        ctx = 'comment';
      } else if (SUPPORTED_SCHEMES.has(doc.uri.scheme)) {
        ctx = await analyzer.classify(doc.languageId, doc, offset);
      } else {
        return; // 输出面板、扩展自定义编辑器等：完全交给用户
      }

      // 语境跟踪必须始终更新：即使当前语境"不干预"（如默认的字符串）。
      // 否则从字符串移回代码时会被"同语境不动作"误拦，手动切成的中文会挂到代码区。
      lastContext.set(editor, ctx);
      lastEvaluatedCtx = ctx;

      // 语境默认目标；代码/字符串语境应用字符一致性（接续打字意图）
      const charAt = (back: number) =>
        offset - back >= 0
          ? doc.getText(new vscode.Range(doc.positionAt(offset - back), doc.positionAt(offset - back + 1)))
          : '';
      const prevChar = charAt(1);
      const prev2Char = charAt(2);
      // 组字保护：微软拼音组字中（"中zhong"）完全静默，任何切换都会打断中文输入
      if (isComposingText(prevChar, prev2Char)) return;
      let target: ImeMode | null;
      if (ctx === 'comment') {
        target = 'chinese';
      } else if (ctx === 'string') {
        if (cfg.stringAsChinese) {
          target = 'chinese';
        } else {
          // 字符一致性收窄：仅"前字符是中文→保持中文"，其余静默（字符串本是不干预语境）
          target = isCjkChar(prevChar) ? 'chinese' : null;
        }
      } else if (ctx === 'code') {
        target = modeFromChar(prevChar) ?? 'english';
      } else {
        target = null;
      }
      if (!target) return;

      // 手动切换覆盖（语境级）：有效期内同语境不切到与用户手动选择相反的模式——
      // 注释里切英文写标识符是正当需求，插件不得抢回中文；
      // 语境变化（移出又移入）后自动恢复。覆盖记录为 null（对话框里切的）则短暂全局让位。
      // 窗口 30 秒（60 秒会让用户感觉"插件失灵不切换了"）
      if (cfg.respectManualToggles && overrideMode && overrideMode !== target &&
          Date.now() - overrideAt < 30000 && (overrideCtx === null || overrideCtx === ctx)) {
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

  // 模式轮询（250ms）：归因用假定模式——观察到的模式 != 插件假定模式 → 用户手动切换。
  // 旧的时间窗口归因（2.5s）会误判：用户在插件注入后 2.5s 内手动切换被当成插件所为，
  // 跟手覆盖不记录 → 插件随后与用户抢输入法
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
    const assumed = assumedMode();
    lastKnownMode = m;
    if (assumed === null || assumed === m) {
      syncAssumedMode(m); // 插件生效，虚拟状态对齐真实
    } else {
      overrideMode = m;
      overrideAt = Date.now();
      overrideCtx = lastEvaluatedCtx; // 记录发生时的语境（null=非编辑器语境，全局让位）
      status.update(m);
    }
  }, 250);
  context.subscriptions.push({ dispose: () => clearInterval(modeTimer) });

  status.update(currentMode());
}
