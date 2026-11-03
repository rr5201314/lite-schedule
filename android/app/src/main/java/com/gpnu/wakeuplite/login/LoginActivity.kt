package com.gpnu.wakeuplite.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.gpnu.wakeuplite.data.Store
import com.gpnu.wakeuplite.data.ZfClient

/**
 * 内嵌 WebView 登录：走学校统一认证，登录成功后从 CookieManager 取正方会话。
 * 不保存账号密码，也不碰验证码，和电脑端复用浏览器登录态同一个思路。
 *
 * 踩过的坑：正方会话 cookie 的 Path 是 /jwglxt，用根路径 URL 去 getCookie 拿不到，
 * 必须按 /jwglxt 下的具体页面 URL 查。这里把几个候选 URL 的 cookie 合并。
 */
class LoginActivity : Activity() {

    private lateinit var web: WebView

    private val probeUrls = listOf(
        "${ZfClient.BASE}/jwglxt/xtgl/index_initMenu.html?jsdm=xs",
        "${ZfClient.BASE}/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default",
        "${ZfClient.BASE}/",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(
                Button(this@LoginActivity).apply {
                    text = "我已完成登录"
                    setOnClickListener { tryCapture(showFailure = true) }
                }
            )
        }
        setContentView(root)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 从认证页跳回教务站内，说明登录链路走通了，直接尝试取会话
                if (url != null && url.contains("jwglxt.gpnu.edu.cn") && !url.contains("/sso/")) {
                    tryCapture(showFailure = false)
                    // 正方是单页应用，页面渲染完再补一次，避免过早取到空 cookie
                    view?.postDelayed({ tryCapture(showFailure = false) }, 1500)
                }
            }
        }
        web.loadUrl(ZfClient.LOGIN_URL)
    }

    /** 把候选 URL 的 cookie 合并成一个串；不同 Path 的会话 cookie 都能覆盖到 */
    private fun collectCookies(): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        val cm = CookieManager.getInstance()
        for (url in probeUrls) {
            val raw = cm.getCookie(url) ?: continue
            for (pair in raw.split(";")) {
                val p = pair.trim()
                if (p.isEmpty()) continue
                val name = p.substringBefore("=", "")
                if (name.isNotEmpty()) merged[name] = p
            }
        }
        return merged
    }

    private fun tryCapture(showFailure: Boolean) {
        val cookies = collectCookies()
        val header = cookies.values.joinToString("; ")

        if (cookies.isNotEmpty()) {
            CookieManager.getInstance().flush()
            Store.setCookie(this, header)
        Store.setSessionState(this, "alive")
            Log.i("WakeupLiteLogin", "saved cookies: " + cookies.keys.joinToString(","))
            Toast.makeText(this, "登录态已保存（${cookies.size} 个 cookie）", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent())
            finish()
            return
        }

        if (showFailure) {
            Toast.makeText(this, "还没拿到教务会话，请确认已进入课表页面", Toast.LENGTH_LONG).show()
            Log.w("WakeupLiteLogin", "no cookies at any probe url")
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
