import * as vscode from 'vscode';
import type { GrammarSource } from './grammarSource';

/**
 * 运行时语法来源：扫描所有已安装/内置扩展的 contributes.grammars，
 * 解析 语言id → scopeName 与 scopeName → 语法文件位置。
 */
export class VscodeGrammarSource implements GrammarSource {
  private byLanguage = new Map<string, string>();
  private byScope = new Map<string, { extId: string; path: string }>();

  constructor() {
    for (const ext of vscode.extensions.all) {
      const grammars = ext.packageJSON?.contributes?.grammars;
      if (!Array.isArray(grammars)) continue;
      for (const g of grammars) {
        if (!g?.scopeName || !g?.path) continue;
        if (!this.byScope.has(g.scopeName)) {
          this.byScope.set(g.scopeName, { extId: ext.id, path: g.path });
        }
        if (g.language && !this.byLanguage.has(g.language)) {
          this.byLanguage.set(g.language, g.scopeName);
        }
      }
    }
  }

  scopeForLanguage(languageId: string): string | undefined {
    return this.byLanguage.get(languageId);
  }

  async readGrammar(scopeName: string): Promise<string | undefined> {
    const meta = this.byScope.get(scopeName);
    if (!meta) return undefined;
    const ext = vscode.extensions.getExtension(meta.extId);
    if (!ext) return undefined;
    try {
      const uri = vscode.Uri.joinPath(ext.extensionUri, meta.path);
      const data = await vscode.workspace.fs.readFile(uri);
      return new TextDecoder('utf-8').decode(data);
    } catch {
      return undefined;
    }
  }
}
