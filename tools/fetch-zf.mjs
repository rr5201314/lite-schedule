// 正方教务课表抓取器（M1 验证版）
// 用法: node tools/fetch-zf.mjs <url> [输出文件名]
// cookie 从 data/cookies.txt 读取（由 agent-browser cookies get 导出）

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const BASE = 'https://jwglxt.gpnu.edu.cn';
const DATA_DIR = resolve(process.cwd(), 'data');

const url = process.argv[2];
const outName = process.argv[3] || 'page.html';
const postIdx = process.argv.indexOf('--post');
const postBody = postIdx > 0 ? process.argv[postIdx + 1] : null;
if (!url) {
  console.error('usage: node tools/fetch-zf.mjs <url> [outName] [--post "a=1&b=2"]');
  process.exit(2);
}

const cookies = readFileSync(resolve(DATA_DIR, 'cookies.txt'), 'utf8').trim();

const headers = {
  'Cookie': cookies,
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
  'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
  'Referer': `${BASE}/jwglxt/xtgl/index_initMenu.html?jsdm=xs`,
};

const target = url.startsWith('http') ? url : BASE + url;
const init = { headers, redirect: 'manual' };
if (postBody) {
  init.method = 'POST';
  init.body = postBody;
  headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
  headers['X-Requested-With'] = 'XMLHttpRequest';
}
const res = await fetch(target, init);
const body = await res.text();

const title = (body.match(/<title>([^<]*)<\/title>/i) || [])[1] || '';
const looks404 = /页面不存在|系统维护|404/.test(body) && body.length < 20000;

console.log(JSON.stringify({
  status: res.status,
  location: res.headers.get('location'),
  contentType: res.headers.get('content-type'),
  bytes: body.length,
  title,
  looks404,
}, null, 2));

mkdirSync(DATA_DIR, { recursive: true });
const out = resolve(DATA_DIR, outName);
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, body, 'utf8');
console.log('saved:', out);
