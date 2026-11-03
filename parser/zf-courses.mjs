// 正方课表 JSON 解析与变更比对

/**
 * 周次表达式 → 周次集合
 * 支持: "第13周" / "1-16周" / "1-4周,6-16周" / "5-7周(单)" / "1-15周(双)"
 */
export function parseWeeks(expr) {
  const weeks = new Set();
  let parity = 'all';
  if (!expr) return { weeks: [], parity, label: '' };

  if (/(单)/.test(expr)) parity = 'odd';
  else if (/(双)/.test(expr)) parity = 'even';

  const cleaned = expr.replace(/\((单|双)\)/g, '').replace(/周/g, '');
  for (const part of cleaned.split(/[,，]/)) {
    const seg = part.trim();
    if (!seg) continue;
    const range = seg.match(/^第?(\d+)\s*-\s*(\d+)$/);
    const single = seg.match(/^第?(\d+)$/);
    if (range) {
      const [a, b] = [Number(range[1]), Number(range[2])];
      for (let w = a; w <= b; w++) weeks.add(w);
    } else if (single) {
      weeks.add(Number(single[1]));
    }
  }

  const list = [...weeks].sort((a, b) => a - b);
  if (parity === 'odd') return { weeks: list.filter((w) => w % 2 === 1), parity, label: expr };
  if (parity === 'even') return { weeks: list.filter((w) => w % 2 === 0), parity, label: expr };
  return { weeks: list, parity, label: expr };
}

/** "2-4" / "3-4" / "9-11" → {start,end} */
export function parseSections(jcs) {
  const m = String(jcs || '').match(/^(\d+)\s*-\s*(\d+)$/);
  if (m) return { start: Number(m[1]), end: Number(m[2]) };
  const single = String(jcs || '').match(/^(\d+)$/);
  if (single) return { start: Number(single[1]), end: Number(single[1]) };
  return { start: 0, end: 0 };
}

const WEEKDAY = { 1: '周一', 2: '周二', 3: '周三', 4: '周四', 5: '周五', 6: '周六', 7: '周日' };

/** 一条课表记录的唯一指纹，用于跨次抓取比对 */
export function signature(c) {
  return [c.name, c.teacher, c.day, c.sections.start, c.sections.end, c.room, c.weeks.label].join('|');
}

export function humanize(c) {
  const time = c.timeStart ? ` ${c.timeStart}-${c.timeEnd}` : '';
  return `${WEEKDAY[c.day] || c.day} 第${c.sections.start}-${c.sections.end}节${time} ${c.name} | ${
    c.teacher || '待定'
  } | ${c.room || '待定'} | ${c.weeks.label || '周次未知'}`;
}

/** 正方返回的 kbList → 稳定的课表模型 */
export function parseKb(json, periods = []) {
  const xsxx = json.xsxx || {};
  const bySection = new Map(periods.map((p) => [p.section, p]));
  const courses = (json.kbList || []).map((raw) => {
    const sections = parseSections(raw.jcs || raw.jc);
    const weeks = parseWeeks(raw.zcd);
    const startPeriod = bySection.get(sections.start);
    const endPeriod = bySection.get(sections.end);
    const course = {
      name: (raw.kcmc || '').trim(),
      teacher: (raw.xm || '').trim(),
      room: (raw.cdmc || '').trim(),
      campus: (raw.xqmc || '').trim(),
      day: Number(raw.xqj) || 0,
      sections,
      weeks,
      timeStart: startPeriod?.start || '',
      timeEnd: endPeriod?.end || '',
      type: raw.kclbmc || raw.kclb || '',
      credit: raw.xf || '',
      classId: raw.jxb_id || '',
      courseCode: raw.kch || '',
      classComposition: raw.jxbzc || '',
    };
    course.sig = signature(course);
    return course;
  });

  return {
    meta: {
      studentId: xsxx.XH || '',
      studentName: xsxx.XM || '',
      className: xsxx.BJMC || '',
      major: xsxx.ZYMC || '',
      academicYear: json.xsxx?.XNM || '',
      academicYearLabel: xsxx.XNMC || '',
      semester: xsxx.XQM || '',
      semesterLabel: xsxx.XQMMC || '',
      campusId: (json.kbList || [])[0]?.xqh_id || '',
    },
    periods,
    courses: courses.sort(
      (a, b) => a.day - b.day || a.sections.start - b.sections.start || a.name.localeCompare(b.name)
    ),
  };
}

/** 节次时间：正方 xskbcx_cxRjc.html 返回 [{jcmc:'1', qssj:'08:30', jssj:'09:10', rsdmc:'上午'}] */
export function parsePeriods(json) {
  const list = Array.isArray(json) ? json : [];
  return list
    .map((p) => ({
      section: Number(p.jcmc) || 0,
      start: p.qssj || '',
      end: p.jssj || '',
      group: p.rsdmc || '',
    }))
    .filter((p) => p.section > 0)
    .sort((a, b) => a.section - b.section);
}

/** 比对两次快照，返回人可读的变更 */
export function diffSnapshots(prev, next) {
  const prevMap = new Map((prev?.courses || []).map((c) => [c.sig, c]));
  const nextMap = new Map((next?.courses || []).map((c) => [c.sig, c]));

  const added = [...nextMap.values()].filter((c) => !prevMap.has(c.sig));
  const removed = [...prevMap.values()].filter((c) => !nextMap.has(c.sig));

  // 同名同教师的一出一进，视为同一门课被调整，而不是两门课
  const changes = [];
  const matchedRemoved = new Set();
  const matchedAdded = new Set();
  for (const a of added) {
    const old = removed.find(
      (r) => !matchedRemoved.has(r.sig) && r.name === a.name && r.teacher === a.teacher
    );
    if (!old) continue;
    const fields = [];
    if (old.day !== a.day) fields.push(`星期 ${old.day} → ${a.day}`);
    if (old.sections.start !== a.sections.start || old.sections.end !== a.sections.end) {
      fields.push(`节次 ${old.sections.start}-${old.sections.end} → ${a.sections.start}-${a.sections.end}`);
    }
    if (old.room !== a.room) fields.push(`地点 ${old.room || '待定'} → ${a.room || '待定'}`);
    if (old.weeks.label !== a.weeks.label) fields.push(`周次 ${old.weeks.label} → ${a.weeks.label}`);
    if (old.teacher !== a.teacher) fields.push(`教师 ${old.teacher} → ${a.teacher}`);
    if (!fields.length) continue;
    matchedRemoved.add(old.sig);
    matchedAdded.add(a.sig);
    changes.push({ kind: 'changed', name: a.name, fields, before: old, after: a });
  }

  return {
    added: added.filter((c) => !matchedAdded.has(c.sig)),
    removed: removed.filter((c) => !matchedRemoved.has(c.sig)),
    changed: changes,
    unchangedCount: [...nextMap.values()].filter((c) => prevMap.has(c.sig)).length,
  };
}

export function formatDiff(diff) {
  const lines = [];
  for (const c of diff.changed) lines.push(`改课  ${c.name}: ${c.fields.join('；')}`);
  for (const c of diff.added) lines.push(`新增  ${humanize(c)}`);
  for (const c of diff.removed) lines.push(`取消  ${humanize(c)}`);
  if (!lines.length) lines.push('课表没有变化');
  return lines.join('\n');
}
