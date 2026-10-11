import * as koffi from 'koffi';

export type ImeMode = 'chinese' | 'english';
export type SwitchResult = 'noop' | 'direct' | 'hotkey' | 'failed';

const IME_CMODE_NATIVE = 0x0001;
const KEYEVENTF_KEYUP = 0x0002;
const VK_SHIFT = 0x10;
const VK_CONTROL = 0x11;
const VK_SPACE = 0x20;

// 轮询校验：总等待 ~420ms，容忍微软拼音经 TSF 应用新模式的滞后
const VERIFY_POLL_COUNT = 6;
const VERIFY_POLL_INTERVAL_MS = 70;

let ready = false;
// koffi 的 .d.ts 对 func() 参数有严格限制，这里统一放宽为 any（运行时 API 以字符串描述类型）
/* eslint-disable @typescript-eslint/no-explicit-any */
let GetForegroundWindowFn: any;
let ImmGetContextFn: any;
let ImmReleaseContextFn: any;
let ImmGetConversionStatusFn: any;
let ImmSetConversionStatusFn: any;
let ImmSetOpenStatusFn: any;
let keybdEventFn: any;

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
    keybdEventFn = user32.func('void __stdcall keybd_event(uint8, uint8, uint32, uintptr)');
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
  for (let i = 0; i < VERIFY_POLL_COUNT; i++) {
    await sleep(VERIFY_POLL_INTERVAL_MS);
    if (currentMode() === target) return true;
  }
  return false;
}

export async function switchTo(
  target: ImeMode,
  hotkeyName: string,
  useDirect: boolean,
): Promise<SwitchResult> {
  if (!ready) return 'failed';
  if (currentMode() === target) return 'noop';

  if (useDirect && directSet(target)) {
    if (await verify(target)) return 'direct';
  }

  const vks = parseHotkey(hotkeyName);
  if (vks.length) {
    try {
      for (const vk of vks) keybdEventFn(vk, 0, 0, 0);
      for (const vk of [...vks].reverse()) keybdEventFn(vk, 0, KEYEVENTF_KEYUP, 0);
    } catch (e) {
      console.error('[CodeIME] 模拟热键失败:', e);
      return 'failed';
    }
    if (await verify(target)) return 'hotkey';
  }
  return 'failed';
}
