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
