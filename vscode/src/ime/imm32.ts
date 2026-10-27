import * as koffi from 'koffi';

export type ImeMode = 'chinese' | 'english';
export type SwitchResult = 'noop' | 'direct' | 'hotkey' | 'failed';

const IME_CMODE_NATIVE = 0x0001;
const KEYEVENTF_KEYUP = 0x0002;
const VK_SHIFT = 0x10;
const VK_CONTROL = 0x11;
const VK_SPACE = 0x20;

// 回读校验间隔序列（毫秒）：先密后疏，累计约 250ms；
// 大多数切换在 30~50ms 内生效，更早的首次回读能更快确认
const VERIFY_POLL_DELAYS_MS = [20, 30, 45, 65, 90];
// 两次注入的最小间隔：避开微软拼音的忽略窗口与 IDE 双击 Shift 窗口
const HOTKEY_MIN_SPACING_MS = 800;

// Win32 INPUT 结构（koffi 原子 SendInput 注入用）
const KEYBDINPUT = koffi.struct('CodeIME_KEYBDINPUT', {
  wVk: 'uint16',
  wScan: 'uint16',
  dwFlags: 'uint32',
  time: 'uint32',
  dwExtraInfo: 'uintptr_t',
});
const MOUSEINPUT = koffi.struct('CodeIME_MOUSEINPUT', {
  dx: 'int32',
  dy: 'int32',
  mouseData: 'uint32',
  dwFlags: 'uint32',
  time: 'uint32',
  dwExtraInfo: 'uintptr_t',
});
const INPUT_UNION = koffi.union('CodeIME_INPUT_U', { ki: KEYBDINPUT, mi: MOUSEINPUT });
const INPUT = koffi.struct('CodeIME_INPUT', { type: 'uint32', u: INPUT_UNION });
const INPUT_SIZE = koffi.sizeof(INPUT);

let ready = false;
// koffi 的 .d.ts 对 func() 参数有严格限制，这里统一放宽为 any（运行时 API 以字符串描述类型）
/* eslint-disable @typescript-eslint/no-explicit-any */
let GetForegroundWindowFn: any;
let ImmGetContextFn: any;
let ImmReleaseContextFn: any;
let ImmGetConversionStatusFn: any;
let ImmSetConversionStatusFn: any;
let ImmSetOpenStatusFn: any;
let SendInputFn: any;
let GetAsyncKeyStateFn: any;

/**
 * 初始化原生绑定（仅 Windows）。与 IDEA 版相同的混合策略：
 * 读 ImmGetConversionStatus 判定状态 → 优先 ImmSet* 直接设置并回读校验 → 降级模拟切换热键。
 */
export function initIme(): boolean {
  if (ready) return true;
  if (process.platform !== 'win32') return false;
  try {
    const imm32: any = koffi.load('imm32.dll');
    const user32: any = koffi.load('user32.dll');
    // koffi 3.x 用原型字符串声明（含调用约定与 _Out_ 输出参数注解）
    GetForegroundWindowFn = user32.func('void* __stdcall GetForegroundWindow()');
    ImmGetContextFn = imm32.func('void* __stdcall ImmGetContext(void*)');
    ImmReleaseContextFn = imm32.func('int __stdcall ImmReleaseContext(void*, void*)');
    ImmGetConversionStatusFn = imm32.func(
      'int __stdcall ImmGetConversionStatus(void*, _Out_ uint32*, _Out_ uint32*)',
    );
    ImmSetConversionStatusFn = imm32.func('int __stdcall ImmSetConversionStatus(void*, uint32, uint32)');
    ImmSetOpenStatusFn = imm32.func('int __stdcall ImmSetOpenStatus(void*, int)');
    SendInputFn = user32.func('uint32 __stdcall SendInput(uint32, CodeIME_INPUT*, int)');
    GetAsyncKeyStateFn = user32.func('int16 __stdcall GetAsyncKeyState(int)');
    ready = true;
  } catch (e) {
    console.error('[CodeIME] 初始化 imm32 失败:', e);
  }
  return ready;
}

/** 当前输入法模式；null 表示无法判定 */
export function currentMode(): ImeMode | null {
  if (!ready) return null;
  let hwnd: unknown = null;
  let himc: unknown = null;
  try {
    hwnd = GetForegroundWindowFn();
    if (!hwnd) return null;
    himc = ImmGetContextFn(hwnd);
    if (!himc) return 'english'; // IME 关闭：按键直接走 ASCII
    const conv = koffi.alloc('uint32', 1);
    const sent = koffi.alloc('uint32', 1);
    if (!ImmGetConversionStatusFn(himc, conv, sent)) return null;
    return (koffi.decode(conv, 'uint32') & IME_CMODE_NATIVE) !== 0 ? 'chinese' : 'english';
  } catch (e) {
    console.error('[CodeIME] 读取输入法状态失败:', e);
    return null;
  } finally {
    if (hwnd && himc) {
      try {
        ImmReleaseContextFn(hwnd, himc);
      } catch {
        // 忽略释放失败
      }
    }
  }
}

/** 直接设置转换模式；返回调用是否成功（不保证生效，需回读校验） */
function directSet(target: ImeMode): boolean {
  let hwnd: unknown = null;
  let himc: unknown = null;
  try {
    hwnd = GetForegroundWindowFn();
    if (!hwnd) return false;
    himc = ImmGetContextFn(hwnd);
    if (!himc) return false;
    const convBuf = koffi.alloc('uint32', 1);
    const sentBuf = koffi.alloc('uint32', 1);
    if (!ImmGetConversionStatusFn(himc, convBuf, sentBuf)) return false;
    const conv = koffi.decode(convBuf, 'uint32');
    const sent = koffi.decode(sentBuf, 'uint32');
    const newConv =
      target === 'chinese' ? conv | IME_CMODE_NATIVE : conv & ~IME_CMODE_NATIVE;
    if (target === 'chinese') ImmSetOpenStatusFn(himc, 1);
    return !!ImmSetConversionStatusFn(himc, newConv, sent);
  } catch (e) {
    console.error('[CodeIME] 直接设置输入法失败:', e);
    return false;
  } finally {
    if (hwnd && himc) {
      try {
        ImmReleaseContextFn(hwnd, himc);
      } catch {
        // 忽略释放失败
      }
    }
  }
}

function parseHotkey(name: string): number[] {
  switch (name) {
    case 'shift':
      return [VK_SHIFT];
    case 'ctrl+space':
      return [VK_CONTROL, VK_SPACE];
    default:
      return [];
  }
}

const sleep = (ms: number) => new Promise<void>(r => setTimeout(r, ms));

/**
 * 轮询式校验：微软拼音经 TSF 应用新模式可能滞后数百毫秒，
 * 单次短延迟回读会误判（实际已切换却被判失败，进而反复模拟热键来回振荡）。
 */
async function verify(target: ImeMode): Promise<boolean> {
  for (const delay of VERIFY_POLL_DELAYS_MS) {
    await sleep(delay);
    if (currentMode() === target) return true;
  }
  return false;
}

let lastHotkeyAt = 0;
// 插件最近一次注入/直接设置的时间戳，用于把模式变化归因给插件或用户。
// 初始化为当前时刻，否则 (Date.now() - 0) 巨大，首次变化会被误判为用户手动切换
let lastSwitchAt = Date.now();
// 假定模式：读不到真实状态（TSF 架构）时用插件自己的切换意图跟踪，
// 避免每次光标事件都盲发热键（连续两次会把中英抵消）
let assumedModeVar: 'chinese' | 'english' | null = null;

/** 插件最近一次切换尝试的时间戳（手动切换跟手机制的归因依据） */
export function lastPluginSwitchAt(): number {
  return lastSwitchAt;
}

/** 当前假定模式（归因用：观察到的模式 != 假定模式 → 用户手动切换） */
export function assumedMode(): ImeMode | null {
  return assumedModeVar;
}

/** 同步假定模式（插件注入生效后，虚拟状态对齐真实状态） */
export function syncAssumedMode(mode: ImeMode): void {
  assumedModeVar = mode;
}

function isKeyHeld(vk: number): boolean {
  if (!GetAsyncKeyStateFn) return false;
  return (GetAsyncKeyStateFn(vk) & 0x8000) !== 0;
}

/**
 * 原子注入一次完整的中英切换热键：
 * 按下+松开在一次 SendInput 中发出（用户按键不会插进按住窗口组合成其他快捷键），
 * 注入前检查用户是否正物理按住相关键（Shift+方向键会扩展选区）。
 */
async function pressHotkey(key: number, mods: number[]): Promise<boolean> {
  if (isKeyHeld(key) || mods.some(isKeyHeld)) return false;
  const sinceLast = Date.now() - lastHotkeyAt;
  if (sinceLast >= 0 && sinceLast < HOTKEY_MIN_SPACING_MS) {
    await sleep(HOTKEY_MIN_SPACING_MS - sinceLast);
  }
  // 等待期间用户可能按下，注入前再确认一次
  if (isKeyHeld(key) || mods.some(isKeyHeld)) return false;
  lastHotkeyAt = Date.now();

  const seq: { vk: number; flags: number }[] = [];
  mods.forEach(vk => seq.push({ vk, flags: 0 }));
  seq.push({ vk: key, flags: 0 });
  seq.push({ vk: key, flags: KEYEVENTF_KEYUP });
  [...mods].reverse().forEach(vk => seq.push({ vk, flags: KEYEVENTF_KEYUP }));

  const buf = koffi.alloc(INPUT, seq.length);
  seq.forEach((ev, i) => {
    koffi.encode(buf, i * INPUT_SIZE, INPUT, {
      type: 1,
      u: { ki: { wVk: ev.vk, wScan: 0, dwFlags: ev.flags, time: 0, dwExtraInfo: 0 } },
    });
  });
  const sent = SendInputFn(seq.length, buf, INPUT_SIZE);
  return sent === seq.length;
}

export async function switchTo(
  target: ImeMode,
  hotkeyName: string,
  useDirect: boolean,
): Promise<SwitchResult> {
  if (!ready) return 'failed';
  const real = currentMode();
  // 读不到真实状态（TSF 架构）时用假定模式判断"是否已处于目标模式"
  const current = real ?? assumedModeVar;
  if (current === target) return 'noop';

  if (useDirect && directSet(target)) {
    assumedModeVar = target;
    if (await verify(target)) return 'direct';
  }

  // 自适应节奏：微软拼音会忽略间隔过短的连续 Shift 切换。
  // 重试间隔 ≥800ms 也避开了双击 Shift 误触全局搜索的判定窗口。
  const vks = parseHotkey(hotkeyName);
  const retryWaits = [0, 800, 2000];
  if (vks.length) {
    const key = vks[vks.length - 1];
    const mods = vks.slice(0, -1);
    for (let attempt = 0; attempt < retryWaits.length; attempt++) {
      await sleep(retryWaits[attempt]);
      const pressed = await pressHotkey(key, mods);
      if (pressed) {
        assumedModeVar = target;
        if (await verify(target)) return 'hotkey';
      }
    }
  }
  return 'failed';
}
