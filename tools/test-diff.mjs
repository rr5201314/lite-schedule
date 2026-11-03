// 变更检测自测：拿当前真实快照造一份"被调课"的假快照，验证 diff 能认出来
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { diffSnapshots, formatDiff } from '../parser/zf-courses.mjs';

const ROOT = resolve(import.meta.dirname, '..');
const cur = JSON.parse(readFileSync(resolve(ROOT, 'data/timetable.json'), 'utf8'));

const fake = JSON.parse(JSON.stringify(cur));

// 1. Java EE 换教室 + 换节次
const jee = fake.courses.find((c) => c.name === 'Java EE程序设计');
jee.room = '三教201（白云校区）';
jee.sections = { start: 6, end: 8 };
jee.sig = 'changed';

// 2. 就业指导（单周那个）被取消
fake.courses = fake.courses.filter((c) => !(c.name === '就业指导（1）' && c.weeks.parity === 'odd'));

// 3. 新增一门课
fake.courses.push({
  name: '新增选修课示例',
  teacher: '示例老师',
  room: '二教101(白云)',
  campus: '白云校区',
  day: 6,
  sections: { start: 1, end: 2 },
  weeks: { weeks: [10, 11, 12], parity: 'all', label: '10-12周' },
  type: '选修',
  sig: 'new-1',
});

console.log(formatDiff(diffSnapshots(cur, fake)));
