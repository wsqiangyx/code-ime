/**
 * 字符一致性：按光标前一个字符推断输入模式（用于"不干预"语境）。
 * CJK/中文标点 → chinese；ASCII 字母 → english；其他 → null（保持现状）
 */
export function isCjkChar(ch: string): boolean {
  if (!ch) return false;
  const v = ch.codePointAt(0)!;
  return (
    (v >= 0x3000 && v <= 0x303f) ||   // CJK 标点
    (v >= 0x4e00 && v <= 0x9fff) ||   // CJK 统一表意文字
    (v >= 0x3400 && v <= 0x4dbf) ||   // 扩展 A
    (v >= 0xf900 && v <= 0xfaff) ||   // 兼容表意文字
    (v >= 0xff00 && v <= 0xffef)      // 全角形式
  );
}

export function modeFromChar(ch: string): 'chinese' | 'english' | null {
  if (!ch) return null;
  if (isCjkChar(ch)) return 'chinese';
  const c = ch.codePointAt(0)!;
  if ((c >= 0x41 && c <= 0x5a) || (c >= 0x61 && c <= 0x7a)) return 'english';
  return null;
}

/**
 * 微软拼音"组字中"检测：CJK 字符后紧跟未上屏的拼音字母（如 "中zhong"）。
 * 组字期间输入法必然处于中文模式且正在编辑，插件此时必须完全静默——
 * 否则字符一致性会把拼音字母判成英文而打断中文输入。
 */
export function isComposingText(prev: string, prev2: string | null): boolean {
  if (!prev) return false;
  const c = prev.codePointAt(0)!;
  const prevIsAsciiLetter = (c >= 0x41 && c <= 0x5a) || (c >= 0x61 && c <= 0x7a);
  if (!prevIsAsciiLetter) return false;
  return !!prev2 && isCjkChar(prev2);
}
