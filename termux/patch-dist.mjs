#!/usr/bin/env node
/**
 * patch-dist.mjs — 可选的第二道保险（shim 失效时才需要）
 *
 * 运行时 shim（omb-shim.cjs）是主方案，且已实测能覆盖 CJS、ESM named import
 * 与异步 link。本脚本只作为兜底：按每个文件真实的 ESM import 绑定名，把
 * linkSync 的调用点改写为 globalThis.__ombLinkSync。
 *
 * 为什么默认只处理 linkSync：
 *   - 打包产物把很多库塞进一个巨型文件，里面 "link" 是常见方法名
 *     （例如 ipaddr.js 的 link(options){}），纯文本替换无法区分作用域，
 *     盲目替换会产生语法错误。实测踩过这个坑，所以异步 link 一律交给 shim。
 *   - linkSync 这个名字足够独特，不会与类方法重名。
 *
 * 为什么按 import 解析绑定名：
 *   打包产物里 linkSync 有大量别名（linkSync2 ... linkSync6），
 *   直接替换 "linkSync(" 会全部漏掉；而 readlinkSync / unlinkSync
 *   都含 linkSync 子串，粗暴替换会破坏代码。
 *
 * 用法：
 *   node patch-dist.mjs <dist-server 目录> [--check]
 *   --check  只报告不改动
 *
 * 幂等：替换后不再匹配正则，可重复运行；上游升级后重新跑一次即可。
 */

import { readFileSync, writeFileSync, readdirSync, statSync, copyFileSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.argv[2];
const CHECK = process.argv.includes('--check');

if (!ROOT) {
  console.error('用法: node patch-dist.mjs <dist-server 目录> [--check]');
  process.exit(2);
}

/** 从 import {...} from "node:fs" 中提取 linkSync 的本地绑定名 */
function collectBindings(src, moduleSpec, exportedName) {
  const names = new Set();
  const re = new RegExp(
    'import\\s*\\{([^}]*)\\}\\s*from\\s*["\']' +
    moduleSpec.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') +
    '["\']', 'g');
  let m;
  while ((m = re.exec(src)) !== null) {
    for (let part of m[1].split(',')) {
      part = part.trim();
      if (!part) continue;
      const asMatch = part.match(/^(\S+)\s+as\s+(\S+)$/);
      const orig = asMatch ? asMatch[1] : part;
      const local = asMatch ? asMatch[2] : part;
      if (orig === exportedName) names.add(local);
    }
  }
  return names;
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (/\.(js|mjs)$/.test(name)) out.push(p);
  }
  return out;
}

/** 找到与 src[openIdx] 的 '(' 配对的 ')'，正确处理字符串与嵌套括号 */
function matchingParen(src, openIdx) {
  let depth = 0, quote = null;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (quote) {
      if (c === '\\') { i++; continue; }
      if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'" || c === '`') { quote = c; continue; }
    if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return i;
  }
  return -1;
}

/** 判断 name( 是否是函数/方法定义（后跟 { 或 =>），这类不能替换 */
function isDefinition(src, nameStart, openIdx) {
  const before = src.slice(Math.max(0, nameStart - 24), nameStart);
  if (/\b(function|async|get|set|class)\s+$/.test(before) || /\*\s*$/.test(before)) return true;
  const close = matchingParen(src, openIdx);
  if (close < 0) return true;
  const after = src.slice(close + 1, close + 12);
  return /^\s*\{/.test(after) || /^\s*=>/.test(after);
}

/** 精确替换某个本地绑定名的调用点 */
function replaceCalls(src, binding, replacement) {
  const esc = binding.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  // 前置断言排除 readlinkSync / unlinkSync / obj.linkSync 等
  const re = new RegExp('(?<![A-Za-z0-9_$.])' + esc + '(?<ws>\\s*)\\(', 'g');
  let count = 0, skippedDef = 0;
  const out = src.replace(re, (match, ws, offset) => {
    const openIdx = offset + match.length - 1;
    if (isDefinition(src, offset, openIdx)) { skippedDef += 1; return match; }
    count += 1;
    return replacement + '(';
  });
  return { out, count, skippedDef };
}

const files = walk(ROOT);
let totalFiles = 0, totalCalls = 0, alreadyPatched = 0, totalSkipped = 0;
const details = [];

for (const file of files) {
  let src = readFileSync(file, 'utf8');
  const original = src;
  const names = collectBindings(src, 'node:fs', 'linkSync');

  let fileCalls = 0;
  for (const n of names) {
    const r = replaceCalls(src, n, 'globalThis.__ombLinkSync');
    src = r.out;
    fileCalls += r.count;
    totalSkipped += r.skippedDef;
  }

  if (src !== original) {
    totalFiles += 1;
    totalCalls += fileCalls;
    if (!CHECK) {
      const bak = file + '.omb-orig';
      if (!existsSync(bak)) copyFileSync(file, bak);
      writeFileSync(file, src);
    }
    details.push(`${relative(ROOT, file)}: ${fileCalls} 处 (${[...names].join(',')})`);
  } else if (src.includes('__ombLinkSync')) {
    alreadyPatched += 1;
  }
}

console.log(`扫描 ${files.length} 个文件`);
console.log(`${CHECK ? '待修改' : '已修改'} ${totalFiles} 个文件，共 ${totalCalls} 处 linkSync 调用点`);
console.log(`已打过补丁（幂等跳过）: ${alreadyPatched} 个文件`);
console.log(`识别为方法定义而跳过: ${totalSkipped} 处`);
console.log('注：异步 link() 调用由运行时 shim 覆盖，本脚本不触碰（避免误伤同名方法）。');
if (details.length) {
  console.log('---- 明细 ----');
  for (const d of details.slice(0, 60)) console.log('  ' + d);
  if (details.length > 60) console.log(`  ...还有 ${details.length - 60} 个文件`);
}
if (totalFiles === 0 && alreadyPatched === 0) {
  console.log('注意：没有找到任何 linkSync 调用点，请确认目录是否是正确的 dist-server。');
  process.exitCode = 1;
}
