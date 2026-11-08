'use strict';
/**
 * omb-shim.cjs — OpenMausBot 在 Android/Termux 上的 link(2) 兼容层
 *
 * 背景：Android 在 app 私有存储里全局禁止 link(2) 硬链接，
 * 调用恒返回 EACCES(errno 13) / EPERM。而 OpenMausBot 用
 *   linkSync(candidate, lease) 靠 EEXIST 判并发来做「数据目录租约」，
 * 以及用 link() 做附件的原子发布，所以 serve 会直接崩在启动阶段。
 *
 * 为什么用 copyFile(..., COPYFILE_EXCL) 而不是 rename：
 *   上游模式是「先写 candidate(flag wx) → 发布到目标 → finally 删 candidate」。
 *   rename 会把 candidate 移走，finally 里的 unlink(candidate) 就会 ENOENT；
 *   copyFile + COPYFILE_EXCL 在目标已存在时同样抛 EEXIST（并发判定不变），
 *   且保留 candidate，语义与 link(2) 对这套用法完全等价。
 *   桌面/普通 Linux 上 linkSync 会成功，本 shim 不做任何事，零行为变化。
 *
 * 注入方式： NODE_OPTIONS="--require /绝对路径/omb-shim.cjs"
 * 由 start-omb.sh 自动设置；对上游发版免疫，不需要改上游源码。
 */

const fs = require('node:fs');
const fsp = require('node:fs/promises');

// 关键：先保存原始实现，否则下面覆盖 fs.linkSync 后 safeLinkSync 会递归调用自己。
const origLinkSync = fs.linkSync;
const origLink = fsp.link;
const origCopyFileSync = fs.copyFileSync;
const origCopyFile = fsp.copyFile;
const EXCL = fs.constants.COPYFILE_EXCL;

const FORBIDDEN = new Set([
  'EACCES', 'EPERM', 'ENOSYS', 'ENOTSUP', 'EOPNOTSUPP', 'ENOPERM',
]);

function isLinkForbidden(err) {
  if (!err) return false;
  if (FORBIDDEN.has(err.code)) return true;
  return /permission denied/i.test(String(err.message || '')) &&
         /link/i.test(String(err.syscall || err.message || ''));
}

let fallbackCount = 0;
function noteFallback(kind, src, dest) {
  fallbackCount += 1;
  if (process.env.OMB_SHIM_DEBUG === '1') {
    process.stderr.write(`[omb-shim] ${kind} 回退 #${fallbackCount}: ${src} -> ${dest}\n`);
  }
}

/** 同步替代 fs.linkSync：正常环境走原生，被禁时回退到 copyFile(EXCL)。 */
function safeLinkSync(src, dest) {
  try {
    return origLinkSync(src, dest);
  } catch (err) {
    if (!isLinkForbidden(err)) throw err;
    noteFallback('linkSync', src, dest);
    return origCopyFileSync(src, dest, EXCL);
  }
}

/** 异步替代 fs.promises.link，语义同上。 */
async function safeLink(src, dest) {
  try {
    return await origLink(src, dest);
  } catch (err) {
    if (!isLinkForbidden(err)) throw err;
    noteFallback('link', src, dest);
    return await origCopyFile(src, dest, EXCL);
  }
}

function install() {
  if (globalThis.__ombShimInstalled) return;
  globalThis.__ombShimInstalled = true;

  // 供文本补丁（patch-dist.mjs）兜底调用
  globalThis.__ombLinkSync = safeLinkSync;
  globalThis.__ombLinkAsync = safeLink;

  const results = {};
  try { fs.linkSync = safeLinkSync; results['fs.linkSync'] = true; }
  catch (e) { results['fs.linkSync'] = String(e.message || e); }

  try { fs.promises.link = safeLink; results['fs.promises.link'] = true; }
  catch (e) { results['fs.promises.link'] = String(e.message || e); }

  try { fsp.link = safeLink; results['fs/promises.link'] = true; }
  catch (e) { results['fs/promises.link'] = String(e.message || e); }

  // ESM 侧：打包产物是 import { linkSync } from "node:fs"。
  // named import 绑定来自 builtin ESM facade，Node 官方 API
  // syncBuiltinESMExports() 会在 monkey-patch 后把 facade 重新对齐，
  // 使 ESM 里拿到的也是替换后的函数（这是本方案对 ESM 生效的关键）。
  try {
    const { syncBuiltinESMExports } = require('node:module');
    syncBuiltinESMExports();
    results['syncBuiltinESMExports'] = true;
  } catch (e) {
    results['syncBuiltinESMExports'] = String(e.message || e);
  }

  if (process.env.OMB_SHIM_DEBUG === '1') {
    process.stderr.write('[omb-shim] 注入结果: ' + JSON.stringify(results) + '\n');
  }
}

install();

module.exports = { safeLinkSync, safeLink };
