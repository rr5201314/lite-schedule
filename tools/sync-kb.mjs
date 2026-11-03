// 课表同步入口：抓取当前学期课表 → 归一化 → 与上次快照比对 → 落盘
// 用法: node tools/sync-kb.mjs [--term 2026:3] [--quiet]

import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { makeClient, parseCurrentTerm } from '../parser/zf-client.mjs';
import { parseKb, parsePeriods, diffSnapshots, formatDiff } from '../parser/zf-courses.mjs';

const ROOT = resolve(import.meta.dirname, '..');
const DATA = resolve(ROOT, 'data');
mkdirSync(DATA, { recursive: true });

const COOKIE_FILE = resolve(DATA, 'cookies.txt');
const SNAPSHOT = resolve(DATA, 'timetable.json');
const PREV = resolve(DATA, 'timetable.prev.json');
const CHANGES = resolve(DATA, 'changes.json');
const RAW = resolve(DATA, 'kb-raw.json');

const args = process.argv.slice(2);
const quiet = args.includes('--quiet');
const termArg = args[args.indexOf('--term') + 1];
const log = (...a) => {
  if (!quiet) console.log(...a);
};

if (!existsSync(COOKIE_FILE)) {
  console.error(`缺少登录态文件: ${COOKIE_FILE}`);
  process.exit(2);
}

const client = makeClient(readFileSync(COOKIE_FILE, 'utf8').trim());

// 1. 当前学年学期
let xnm;
let xqm;
if (termArg && termArg.includes(':')) {
  [xnm, xqm] = termArg.split(':');
} else {
  const page = await client.get('/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default');
  if (page.status !== 200 || /登录|统一身份认证/.test(page.text.slice(0, 400))) {
    console.error('登录态已失效，需要重新从浏览器导出 cookies.txt');
    process.exit(3);
  }
  ({ xnm, xqm } = parseCurrentTerm(page.text));
  if (!xnm || !xqm) {
    console.error('未能在课表页解析出当前学年学期');
    process.exit(4);
  }
}
log(`学年学期: ${xnm}-${Number(xnm) + 1} 第${xqm === '3' ? 1 : xqm === '12' ? 2 : 3}学期`);

// 2. 拉课表
const res = await client.postForm('/jwglxt/kbcx/xskbcx_cxXsKb.html', {
  xnm,
  xqm,
  kzlx: 'ck',
});
if (res.status !== 200 || !res.text.trim().startsWith('{')) {
  console.error(`课表接口异常: HTTP ${res.status}, ${res.text.slice(0, 200)}`);
  process.exit(5);
}

const json = JSON.parse(res.text);
writeFileSync(RAW, res.text, 'utf8');

// 2.5 节次作息时间（第几节是几点到几点），拿不到不影响主流程
let periods = [];
const campusId = (json.kbList || [])[0]?.xqh_id;
if (campusId) {
  try {
    const pr = await client.postForm('/jwglxt/kbcx/xskbcx_cxRjc.html', { xnm, xqm, xqh_id: campusId });
    const parsed = JSON.parse(pr.text);
    periods = parsePeriods(parsed);
    writeFileSync(resolve(DATA, 'periods.json'), JSON.stringify(periods, null, 2), 'utf8');
  } catch (e) {
    log(`作息时间获取失败（不影响课表）: ${e.message}`);
  }
}

const snapshot = parseKb(json, periods);
snapshot.term = { xnm, xqm };
snapshot.fetchedAt = new Date().toISOString();

// 3. 比对
const prev = existsSync(SNAPSHOT) ? JSON.parse(readFileSync(SNAPSHOT, 'utf8')) : null;
const diff = diffSnapshots(prev, snapshot);
if (prev) writeFileSync(PREV, JSON.stringify(prev, null, 2), 'utf8');
writeFileSync(SNAPSHOT, JSON.stringify(snapshot, null, 2), 'utf8');
writeFileSync(CHANGES, JSON.stringify({ fetchedAt: snapshot.fetchedAt, prevAt: prev?.fetchedAt || null, diff }, null, 2), 'utf8');

log(`学生: ${snapshot.meta.studentName} (${snapshot.meta.studentId}) ${snapshot.meta.className}`);
log(`课程条目: ${snapshot.courses.length}，未变: ${diff.unchangedCount}`);
log('--- 变更 ---');
log(formatDiff(diff));
log(`快照: ${SNAPSHOT}`);
