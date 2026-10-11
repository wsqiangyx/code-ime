import * as fs from 'node:fs';
import * as path from 'node:path';

/** 语法来源抽象：语言 id → scopeName；scopeName → 语法原文 */
export interface GrammarSource {
  scopeForLanguage(languageId: string): string | undefined;
  readGrammar(scopeName: string): Promise<string | undefined>;
}

interface GrammarMeta {
  scopeName: string;
  path: string;
}

/**
 * 从 VSCode 安装目录读取内置语法。
 * 用于独立测试环境（无 vscode API），目录形如 <VSCode>/app/resources/extensions。
 */
export class FsGrammarSource implements GrammarSource {
  private meta: GrammarMeta[] = [];
  private byLanguage = new Map<string, string>();

  constructor(private root: string) {
    for (const dir of fs.readdirSync(root)) {
      const pkgPath = path.join(root, dir, 'package.json');
      if (!fs.existsSync(pkgPath)) continue;
      try {
        const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
        for (const g of pkg?.contributes?.grammars ?? []) {
          if (!g?.scopeName || !g?.path) continue;
          // g.path 相对于扩展目录，这里预拼接成绝对路径
          this.meta.push({ scopeName: g.scopeName, path: path.join(root, dir, g.path) });
          if (g.language && !this.byLanguage.has(g.language)) {
            this.byLanguage.set(g.language, g.scopeName);
          }
        }
      } catch {
        // 忽略无法解析的扩展
      }
    }
  }

  scopeForLanguage(languageId: string): string | undefined {
    return this.byLanguage.get(languageId);
  }

  async readGrammar(scopeName: string): Promise<string | undefined> {
    const m = this.meta.find(x => x.scopeName === scopeName);
    if (!m) return undefined;
    try {
      return fs.readFileSync(m.path, 'utf8');
    } catch {
      return undefined;
    }
  }
}
