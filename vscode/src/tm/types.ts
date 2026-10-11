/** 光标上下文 */
export type EditorContext = 'comment' | 'string' | 'code' | 'unknown';

/** 对 TextDocument 的最小抽象，便于脱离 VSCode 运行时做独立测试 */
export interface DocLike {
  version: number;
  lineCount: number;
  lineAt(line: number): { text: string };
  positionAt(offset: number): { line: number; character: number };
}
