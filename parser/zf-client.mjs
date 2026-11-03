// 正方教务 HTTP 客户端（复用浏览器登录态导出的 cookie）
// 这个文件以后要原样移植到安卓端，所以只用标准 fetch，不依赖 Node 专有 API 之外的库。

export const BASE = 'https://jwglxt.gpnu.edu.cn';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';

export function makeClient(cookieHeader) {
  const headers = {
    Cookie: cookieHeader,
    'User-Agent': UA,
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    Referer: `${BASE}/jwglxt/xtgl/index_initMenu.html?jsdm=xs`,
  };

  async function get(path) {
    const res = await fetch(path.startsWith('http') ? path : BASE + path, {
      headers,
      redirect: 'manual',
    });
    return { status: res.status, url: res.url, text: await res.text() };
  }

  async function postForm(path, params) {
    const res = await fetch(path.startsWith('http') ? path : BASE + path, {
      method: 'POST',
      headers: {
        ...headers,
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'X-Requested-With': 'XMLHttpRequest',
      },
      body: new URLSearchParams(params).toString(),
      redirect: 'manual',
    });
    return { status: res.status, text: await res.text() };
  }

  return { get, postForm };
}

// 从课表首页的下拉框取当前学年/学期（页面里已经选中好）
export function parseCurrentTerm(html) {
  const xnm = html.match(/<option value="(\d{4})" selected="selected">/)?.[1];
  const xqm = html.match(/<select name="xqm"[\s\S]*?<option value="(\d+)" selected="selected">/)?.[1];
  return { xnm, xqm };
}
