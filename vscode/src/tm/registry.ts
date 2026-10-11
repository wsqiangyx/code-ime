import * as tm from 'vscode-textmate';
import type { GrammarSource } from './grammarSource';

export type OnigLibLoader = () => Promise<tm.IOnigLib>;

/** TextMate 语法注册表：按语言 id 取语法，按 scopeName 懒加载语法文件 */
export class TmRegistry {
  private registry: tm.Registry;
  private grammarPromises = new Map<string, Promise<tm.IGrammar | null>>();

  constructor(private source: GrammarSource, loadOnig: OnigLibLoader) {
    this.registry = new tm.Registry({
      onigLib: loadOnig(),
      loadGrammar: async (scopeName) => {
        const raw = await this.source.readGrammar(scopeName);
        if (raw === undefined) return null;
        return tm.parseRawGrammar(raw, `${scopeName}.tmLanguage.json`);
      },
    });
  }

  /** 获取某语言对应的语法；无语法返回 null（调用方应跳过切换） */
  grammarFor(languageId: string): Promise<tm.IGrammar | null> {
    let p = this.grammarPromises.get(languageId);
    if (!p) {
      const scope = this.source.scopeForLanguage(languageId);
      p = scope ? this.registry.loadGrammar(scope) : Promise.resolve(null);
      this.grammarPromises.set(languageId, p);
    }
    return p;
  }
}
