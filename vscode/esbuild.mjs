import esbuild from 'esbuild';
import { copyFileSync, mkdirSync } from 'node:fs';

const shared = {
  bundle: true,
  platform: 'node',
  target: 'node20',
  format: 'cjs',
  external: ['koffi', 'vscode'],
  sourcemap: false,
  logLevel: 'info',
};

// 扩展主入口（vscode-textmate / vscode-oniguruma 打进包内，koffi 保持外部）
await esbuild.build({
  ...shared,
  entryPoints: ['src/extension.ts'],
  outfile: 'dist/extension.js',
});

// 独立测试入口（脱离 VSCode 运行时，用 node test-dist/harness.cjs 执行）
await esbuild.build({
  ...shared,
  entryPoints: ['test/harness.ts'],
  outfile: 'test-dist/harness.cjs',
});

mkdirSync('dist', { recursive: true });
copyFileSync('node_modules/vscode-oniguruma/release/onig.wasm', 'dist/onig.wasm');
