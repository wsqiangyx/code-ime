import type * as tm from 'vscode-textmate';
import type { DocLike, EditorContext } from './types';
import type { TmRegistry } from './registry';

/** 每文档的行状态缓存：states[i] = 第 i 行开始前的语法状态 */
class DocCache {
  version: number;
  private states: (tm.StateStack | null)[] = [null];

  constructor(private grammar: tm.IGrammar, private doc: DocLike, version: number) {
    this.version = version;
  }

  /** 文档变更后从第一处变更行截断（该行及之后按需重算） */
  invalidate(firstChangedLine: number | null) {
    const keep = firstChangedLine === null ? 1 : Math.max(1, firstChangedLine);
    if (this.states.length > keep) this.states.length = keep;
  }

  stateBefore(line: number): tm.StateStack | null {
    while (this.states.length <= line) {
      const next = this.states.length;
      const res = this.grammar.tokenizeLine(this.doc.lineAt(next - 1).text, this.states[next - 1]);
      this.states.push(res.ruleStack);
    }
    return this.states[line];
  }
}

/**
 * 基于 TextMate token scope 的上下文判定：
 * 光标所在 token 的 scope 含 comment.* → 注释；string.* → 字符串；否则为代码。
 * 多行注释/字符串由语法的行间状态天然支持。
 */
export class ImeAnalyzer {
  private caches = new WeakMap<DocLike, DocCache>();

  constructor(private registry: TmRegistry) {}

  /** 文档变更时同步失效缓存 */
  invalidate(doc: DocLike, firstChangedLine: number | null) {
    const cache = this.caches.get(doc);
    if (cache && cache.version !== doc.version) {
      cache.version = doc.version;
      cache.invalidate(firstChangedLine);
    }
  }

  async classify(languageId: string, doc: DocLike, offset: number): Promise<EditorContext> {
    const grammar = await this.registry.grammarFor(languageId);
    if (!grammar) return 'unknown';

    let cache = this.caches.get(doc);
    if (!cache) {
      cache = new DocCache(grammar, doc, doc.version);
      this.caches.set(doc, cache);
    }
    if (cache.version !== doc.version) {
      cache.version = doc.version;
      cache.invalidate(null);
    }

    const pos = doc.positionAt(offset);
    const line = Math.min(pos.line, doc.lineCount - 1);
    const state = cache.stateBefore(line);
    const result = grammar.tokenizeLine(doc.lineAt(line).text, state);

    const rel = pos.character;
    const token =
      result.tokens.find(t => rel >= t.startIndex && rel < t.endIndex) ??
      result.tokens[result.tokens.length - 1];
    if (!token) return 'code';

    if (hasScopePrefix(token.scopes, 'comment.')) return 'comment';
    if (hasScopePrefix(token.scopes, 'string.')) return 'string';

    // 光标落在空白里（行首缩进、行内注释前的空隙）：看同行下一个非空白 token。
    // 典型场景：方向键从代码行移到缩进更深的注释行，光标停在缩进空白里，
    // 该 token 不带 comment scope，必须向前看同行的注释 token。
    const lineText = doc.lineAt(line).text;
    if (lineText.slice(token.startIndex, token.endIndex).trim() === '') {
      const firstNonWs = lineText.search(/\S/);
      if (firstNonWs >= 0) {
        const nextToken = result.tokens.find(
          t => firstNonWs >= t.startIndex && firstNonWs < t.endIndex,
        );
        if (nextToken && hasScopePrefix(nextToken.scopes, 'comment.')) return 'comment';
      }
    }
    return 'code';
  }
}

function hasScopePrefix(scopes: string[], prefix: string): boolean {
  for (let i = scopes.length - 1; i >= 0; i--) {
    if (scopes[i].startsWith(prefix)) return true;
  }
  return false;
}
