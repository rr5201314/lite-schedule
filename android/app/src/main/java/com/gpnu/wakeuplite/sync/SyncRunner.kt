package com.gpnu.wakeuplite.sync

import android.content.Context
import com.gpnu.wakeuplite.data.CalendarResolver
import com.gpnu.wakeuplite.data.Change
import com.gpnu.wakeuplite.data.Diff
import com.gpnu.wakeuplite.data.Store
import com.gpnu.wakeuplite.data.ZfClient
import com.gpnu.wakeuplite.notify.Notifier
import com.gpnu.wakeuplite.widget.TodayWidgetProvider
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
}
