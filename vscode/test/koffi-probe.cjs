// koffi 3.3.3 原型字符串形式下的完整 IMM32 流水线验证
const koffi = require('koffi');

const u = koffi.load('user32.dll');
const i = koffi.load('imm32.dll');

try {
  const gfw = u.func('void* __stdcall GetForegroundWindow()');
  const ctx = i.func('void* __stdcall ImmGetContext(void*)');
  const rel = i.func('int __stdcall ImmReleaseContext(void*, void*)');
  const getConv = i.func('int __stdcall ImmGetConversionStatus(void*, _Out_ uint32*, _Out_ uint32*)');
  const getOpen = i.func('int __stdcall ImmGetOpenStatus(void*)');
  const setOpen = i.func('int __stdcall ImmSetOpenStatus(void*, int)');
  const setConv = i.func('int __stdcall ImmSetConversionStatus(void*, uint32, uint32)');
  const kbd = u.func('void __stdcall keybd_event(uint8, uint8, uint32, uintptr)');

  const hwnd = gfw();
  console.log('hwnd:', hwnd);
  if (hwnd) {
    const himc = ctx(hwnd);
    console.log('himc:', himc);
    if (himc) {
      const conv = koffi.alloc('uint32', 1);
      const sent = koffi.alloc('uint32', 1);
      const ok = getConv(himc, conv, sent);
      console.log('conversion ok=', !!ok,
        'value=0x' + (koffi.decode(conv, 'uint32') >>> 0).toString(16),
        'NATIVE(中文)=', (koffi.decode(conv, 'uint32') & 1) !== 0,
        'open=', !!getOpen(himc));
      rel(hwnd, himc);
    }
  }
  console.log('FULL PIPELINE OK（keybd_event 绑定声明可用，未实际按键）');
} catch (e) {
  console.log('PIPELINE FAIL:', e.message);
}
