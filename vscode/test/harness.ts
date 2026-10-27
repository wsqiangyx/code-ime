import * as fs from 'node:fs';
import * as path from 'node:path';
import * as oniguruma from 'vscode-oniguruma';
import { FsGrammarSource } from '../src/tm/grammarSource';
import { TmRegistry } from '../src/tm/registry';
import { ImeAnalyzer } from '../src/tm/analyzer';
import { isComposingText, modeFromChar } from '../src/char-mode';

const VSCODE_EXTENSIONS =
  process.env.VSCODE_EXTENSIONS ?? 'D:/software/VSCode/6d62b1d5da/resources/app/extensions';

interface Case {
  lang: string;
  code: string;
  expected: 'comment' | 'string' | 'code';
  note?: string;
}

/** '@' 标记光标位置，评测前移除 */
const cases: Case[] = [
  { lang: 'javascript', code: 'let a = 1;@', expected: 'code' },
  { lang: 'javascript', code: '// 中文注释@', expected: 'comment' },
  { lang: 'javascript', code: '/* block\n   comment@ */', expected: 'comment' },
  { lang: 'javascript', code: 'const s = "he@llo";', expected: 'string' },
  { lang: 'javascript', code: 'const x = "http://e@xample.com";', expected: 'string' },
  { lang: 'javascript', code: 'const t = `a${b}@c`;', expected: 'string', note: '模板字符串（${} 嵌入代码 v1 仍按字符串）' },
  { lang: 'javascript', code: '/*\n多行\n@*/\nlet a;', expected: 'comment' },
  { lang: 'javascript', code: '// a\n\n@let b;', expected: 'code', note: '注释后的空行+代码' },
  { lang: 'css', code: '/* 注释@ */', expected: 'comment' },
  { lang: 'css', code: 'body { color: red;@ }', expected: 'code' },
  { lang: 'html', code: '<!-- 注释@ -->', expected: 'comment' },
  { lang: 'html', code: '<div>text@</div>', expected: 'code' },
  { lang: 'html', code: '<style>/* c@ */</style>', expected: 'comment', note: '嵌入式 CSS' },
  { lang: 'html', code: '<script>// c@\nlet a;</script>', expected: 'comment', note: '嵌入式 JS' },
  { lang: 'java', code: 'int x = 1;@', expected: 'code' },
  { lang: 'java', code: '/** doc@ */', expected: 'comment' },
  { lang: 'java', code: 'String s = "a@";', expected: 'string' },
  { lang: 'python', code: 'x = 1@', expected: 'code' },
  { lang: 'python', code: '# 注释@', expected: 'comment' },
  { lang: 'python', code: '"""docstring@"""', expected: 'string' },
  { lang: 'python', code: 'x = """多行\n字符串@"""', expected: 'string' },
  { lang: 'javascript', code: '  @// 缩进注释', expected: 'comment', note: '方向键落在行首缩进（回归：代码行下移到缩进更深的注释行）' },
  { lang: 'javascript', code: 'let a = 1; @// 行尾注释', expected: 'comment', note: '注释前的空隙' },
  { lang: 'javascript', code: 'let a = 1; @ b;', expected: 'code', note: '代码参数间空隙仍是代码' },
  { lang: 'java', code: '    @int x = 1;', expected: 'code', note: '代码行行首缩进仍是代码' },
  { lang: 'python', code: '    @# 注释', expected: 'comment', note: 'Python 缩进注释' },
  { lang: 'html', code: '  @<!-- 注释 -->', expected: 'comment', note: 'HTML 缩进注释' },
  // === v1.5.0 新增边界用例 ===
  { lang: 'javascript', code: '//@', expected: 'comment', note: '仅 // 无内容（文件末尾）' },
  { lang: 'javascript', code: '// @', expected: 'comment', note: '// 后只有空格' },
  { lang: 'javascript', code: '// 注释A\n// 注释B@\n// 注释C', expected: 'comment', note: '连续注释行中间' },
  { lang: 'javascript', code: 'foo() // @行尾注释紧贴代码', expected: 'comment', note: '行尾注释光标在 // 后' },
  { lang: 'javascript', code: 'const s = "//@not a comment";', expected: 'string', note: '字符串内的 // 应判为字符串' },
  { lang: 'javascript', code: 'const s = "/*@not a comment*/";', expected: 'string', note: '字符串内的块注释符号' },
  { lang: 'javascript', code: '/* 块注释\n@*/\ncode();', expected: 'comment', note: '块注释最后一行' },
  { lang: 'javascript', code: '@/* 文件开头就是注释 */', expected: 'comment', note: '第 0 行注释' },
  { lang: 'javascript', code: 'function f(/* 参数注释@ */) {}', expected: 'comment', note: '函数参数中的注释' },
  { lang: 'python', code: '#@', expected: 'comment', note: '仅 # 无内容' },
  { lang: 'python', code: '# @中文注释末尾', expected: 'comment', note: '# 后空格+中文' },
  { lang: 'python', code: 'def f():\n    """doc\n    多行@文档\n    """\n    pass', expected: 'string', note: '多行 docstring 中间行（Python）' },
  { lang: 'python', code: 'x = "中@文字符串"', expected: 'string', note: '含中文字符的字符串' },
  { lang: 'css', code: '/* 多行\n   CSS\n   注释@ */\nbody {}', expected: 'comment', note: '多行 CSS 注释' },
  { lang: 'html', code: '<!--\n多行\nHTML\n注释@\n-->\n<div></div>', expected: 'comment', note: '多行 HTML 注释' },
];

// ===== 字符一致性规则（modeFromChar）=====
function testCharMode(): { pass: number; fail: number } {
  const cases: [string, string | null, string][] = [
    ['中', 'chinese', '汉字 → 中文'],
    ['。', 'chinese', '中文句号 → 中文'],
    ['：', 'chinese', '全角冒号 → 中文'],
    ['a', 'english', 'ASCII 字母 → 英文'],
    ['Z', 'english', '大写字母 → 英文'],
    ['3', null, '数字 → 保持现状'],
    [' ', null, '空格 → 保持现状'],
    ['-', null, '连字符 → 保持现状'],
    ['', null, '空 → 保持现状'],
  ];
  let pass = 0, fail = 0;
  for (const [ch, expected, note] of cases) {
    const got = modeFromChar(ch);
    const ok = got === expected;
    if (ok) pass++; else fail++;
    console.log(`${ok ? 'PASS' : 'FAIL'} [char] '${ch}' expected=${expected} got=${got}  (${note})`);
  }
  // 组字保护用例
  const composing: [string, string | null, boolean, string][] = [
    ['g', '中', true, '"中zhong" 组字中 → 静默'],
    ['n', '中', true, '"中" 后接拼音 → 静默'],
    ['a', '文', true, '"文abc" 组字中 → 静默'],
    ['g', 'b', false, '普通英文单词 "bg" → 不算组字'],
    ['中', null, false, '前字符是汉字本身 → 不算组字'],
    ['"', '中', false, '引号前是汉字 → 不算组字'],
  ];
  for (const [prev, prev2, expected, note] of composing) {
    const got = isComposingText(prev, prev2);
    const ok = got === expected;
    if (ok) pass++; else fail++;
    console.log(`${ok ? 'PASS' : 'FAIL'} [composing] prev='${prev}' prev2='${prev2}' expected=${expected} got=${got}  (${note})`);
  }
  return { pass, fail };
}

function makeDoc(text: string) {
  const lines = text.split('\n');
  const lineStarts: number[] = [];
  let acc = 0;
  for (const l of lines) {
    lineStarts.push(acc);
    acc += l.length + 1;
  }
  return {
    version: 1,
    lineCount: lines.length,
    lineAt: (i: number) => ({ text: lines[i] }),
    positionAt: (offset: number) => {
      let line = 0;
      for (let i = 0; i < lineStarts.length; i++) {
        if (offset >= lineStarts[i]) line = i;
      }
      return { line, character: offset - lineStarts[line] };
    },
  };
}

async function main() {
  const wasmPath = path.join(__dirname, '..', 'node_modules', 'vscode-oniguruma', 'release', 'onig.wasm');
  const buf = fs.readFileSync(wasmPath);
  await oniguruma.loadWASM(
    buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) as ArrayBuffer,
  );

  const source = new FsGrammarSource(VSCODE_EXTENSIONS);
  const registry = new TmRegistry(source, () =>
    Promise.resolve({
      createOnigScanner: (sources: string[]) => new oniguruma.OnigScanner(sources),
      createOnigString: (str: string) => new oniguruma.OnigString(str),
    }),
  );
  const analyzer = new ImeAnalyzer(registry);

  let pass = 0;
  let fail = 0;
  // 字符一致性规则
  const cm = testCharMode();
  pass += cm.pass; fail += cm.fail;

  for (const c of cases) {
    const marker = c.code.indexOf('@');
    if (marker < 0) throw new Error('case 缺少 @ 标记: ' + c.code);
    const text = c.code.replace('@', '');
    const doc = makeDoc(text);
    const got = await analyzer.classify(c.lang, doc, marker);
    const ok = got === c.expected;
    if (ok) pass++;
    else fail++;
    console.log(
      `${ok ? 'PASS' : 'FAIL'} [${c.lang}] ${JSON.stringify(c.code.slice(0, 44))} expected=${c.expected} got=${got}${c.note ? '  (' + c.note + ')' : ''}`,
    );
  }

  // koffi 冒烟：加载 user32 并验证原子 SendInput（无害键 F15，不切换输入法）
  try {
    const koffi = require('koffi');
    const u = koffi.load('user32.dll');
    const KI = koffi.struct('Harness_KEYBDINPUT', {
      wVk: 'uint16', wScan: 'uint16', dwFlags: 'uint32', time: 'uint32', dwExtraInfo: 'uintptr_t',
    });
    const MI = koffi.struct('Harness_MOUSEINPUT', {
      dx: 'int32', dy: 'int32', mouseData: 'uint32', dwFlags: 'uint32', time: 'uint32', dwExtraInfo: 'uintptr_t',
    });
    const U = koffi.union('Harness_INPUT_U', { ki: KI, mi: MI });
    const INP = koffi.struct('Harness_INPUT', { type: 'uint32', u: U });
    const send = u.func('uint32 __stdcall SendInput(uint32, Harness_INPUT*, int)');
    const buf = koffi.alloc(INP, 2);
    koffi.encode(buf, 0, INP, { type: 1, u: { ki: { wVk: 0x7e, wScan: 0, dwFlags: 0, time: 0, dwExtraInfo: 0 } } });
    koffi.encode(buf, 1, INP, { type: 1, u: { ki: { wVk: 0x7e, wScan: 0, dwFlags: 2, time: 0, dwExtraInfo: 0 } } });
    const sent = send(2, buf, koffi.sizeof(INP));
    console.log(`KOFFI smoke: user32 loaded, SendInput(F15) sent=${sent} (期望 2)`);
  } catch (e) {
    console.log('KOFFI smoke failed:', e);
  }

  console.log(`\n${pass} passed, ${fail} failed`);
  if (fail > 0) process.exit(1);
}

void main();
