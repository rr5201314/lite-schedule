package io.github.rr5201314.liteschedule.sync

import android.content.Context
import io.github.rr5201314.liteschedule.data.CalendarResolver
import io.github.rr5201314.liteschedule.data.Change
import io.github.rr5201314.liteschedule.data.Diff
import io.github.rr5201314.liteschedule.data.Period
import io.github.rr5201314.liteschedule.data.Store
import io.github.rr5201314.liteschedule.data.ZfClient
import io.github.rr5201314.liteschedule.notify.Notifier
import io.github.rr5201314.liteschedule.widget.TodayWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 同步的实际执行体。Worker 和界面上的「立即同步」共用这一份逻辑，
 * 这样界面能拿到明确的结果文案，而不是发完任务就什么都不知道。
 */
object SyncRunner {

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val changes: List<Change> = emptyList(),
        val courseCount: Int = 0,
    )

    suspend fun run(ctx: Context): Outcome = withContext(Dispatchers.IO) {
        val cookie = Store.getCookie(ctx)
            ?: return@withContext Outcome(false, "还没登录教务，先点「登录教务」")

        runCatching {
            val snapshot = ZfClient.fetchTimetable(cookie)
            Store.setSessionState(ctx, "alive")
            val prev = Store.loadSnapshot(ctx)
            val changes = Diff.compute(prev, snapshot)
            Store.saveSnapshot(ctx, snapshot)

            // 学期变了或者还没有第 1 周，就按教务部公开通知自动校准一次
            val termKey = "${snapshot.xnm}-${snapshot.xqm}"
            var autoCalibrated = false
            if (Store.getWeek1(ctx) <= 0L || Store.getWeek1Term(ctx) != termKey) {
                val resolved = CalendarResolver.resolve(snapshot.xnm, snapshot.xqm)
                if (resolved != null) {
                    Store.setWeek1(ctx, resolved.week1EpochDay)
                    Store.setWeek1Term(ctx, termKey)
                    autoCalibrated = true
                }
            }

            if (changes.isNotEmpty()) Notifier.notifyChanges(ctx, changes)
            TodayWidgetProvider.refreshAll(ctx)

            val suffix = if (autoCalibrated) "（已自动校准第1周）" else ""
            val message = when {
                prev == null -> "同步成功，已拉取 ${snapshot.courses.size} 条排课$suffix"
                changes.isEmpty() -> "同步完成，课表没有变化$suffix"
                else -> "同步完成，发现 ${changes.size} 处变化$suffix"
            }
            Outcome(true, message, changes, snapshot.courses.size)
        }.getOrElse { e ->
            val msg = e.message ?: "同步失败"
            val needLogin = msg.contains("登录") || msg.contains("失效") || msg.contains("认证")
            if (needLogin) Store.setSessionState(ctx, "expired")
            Outcome(false, if (needLogin) "登录已过期，点右上角 ⋮ → 登录教务 重新登录一次" else "同步失败：$msg")
        }
    }

    /**
     * 把所有学期的课表一次性拉回来存档（登录后跑一次就够，之后只更新当前学期）。
     * 返回 (成功保存的学期数, 提示文案)。
     */
    suspend fun syncAllSemesters(ctx: Context): Pair<Int, String> = withContext(Dispatchers.IO) {
        val cookie = Store.getCookie(ctx) ?: return@withContext 0 to "还没登录教务"
        runCatching {
            val page = ZfClient.get(cookie, ZfClient.KB_PAGE)
            val all = ZfClient.parseTermOptions(page)
            if (all.isEmpty()) return@runCatching 0 to "没解析到学期列表"

            // 只回看最近 6 年，避免一次打上百个请求
            val thisYear = java.time.LocalDate.now().year
            val terms = all.filter { (y, _) -> y.toIntOrNull()?.let { it >= thisYear - 6 } == true }
            val periodsCache = mutableMapOf<String, List<Period>>()
            var saved = 0
            for ((y, t) in terms) {
                val snap = runCatching { ZfClient.fetchTimetableFor(cookie, y, t, periodsCache) }.getOrNull() ?: continue
                Store.upsertArchive(ctx, snap)
                saved++
            }
            saved to "已保存 $saved 个学期的课表"
        }.getOrElse { 0 to ("拉取失败：" + (it.message ?: "未知错误")) }
    }

}
