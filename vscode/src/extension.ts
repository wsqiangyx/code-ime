import * as vscode from 'vscode';
import * as oniguruma from 'vscode-oniguruma';
import { initIme, currentMode } from './ime/imm32';
import { TmRegistry } from './tm/registry';
import { ImeAnalyzer } from './tm/analyzer';
import { VscodeGrammarSource } from './tm/vscodeGrammarSource';
import { StatusBarController } from './status';
import { registerListeners } from './listener';

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const status = new StatusBarController(false);
  context.subscriptions.push(status);

  const immAvailable = initIme();

  context.subscriptions.push(
    vscode.commands.registerCommand('codeIME.toggleEnabled', async () => {
      const c = vscode.workspace.getConfiguration('codeIME');
      const newValue = !c.get<boolean>('enabled', true);
      await c.update('enabled', newValue, vscode.ConfigurationTarget.Global);
      void vscode.window.showInformationMessage(`CodeIME 自动切换已${newValue ? '启用' : '禁用'}`);
    }),
  );

  if (!immAvailable) {
    status.update(null);
    return;
  }

  // 加载 oniguruma WASM（打包时已复制到 dist/）
  const wasmData = await vscode.workspace.fs.readFile(
    vscode.Uri.joinPath(context.extensionUri, 'dist', 'onig.wasm'),
  );
  const wasmBin = new Uint8Array(wasmData).buffer.slice(
    wasmData.byteOffset,
    wasmData.byteOffset + wasmData.byteLength,
  ) as ArrayBuffer;
  await oniguruma.loadWASM(wasmBin);

  const registry = new TmRegistry(new VscodeGrammarSource(), () =>
    Promise.resolve({
      createOnigScanner: (sources: string[]) => new oniguruma.OnigScanner(sources),
      createOnigString: (str: string) => new oniguruma.OnigString(str),
    }),
  );
  const analyzer = new ImeAnalyzer(registry);
  registerListeners(context, analyzer, status);
}

export function deactivate(): void {
  // 无需清理：订阅项随 context 自动释放
}
