package io.github.rr5201314.liteschedule.sync

import android.content.Context
import io.github.rr5201314.liteschedule.data.CalendarResolver
import io.github.rr5201314.liteschedule.data.Change
import io.github.rr5201314.liteschedule.data.Diff
import io.github.rr5201314.liteschedule.data.Period
import io.github.rr5201314.liteschedule.data.Store
import io.github.rr5201314.liteschedule.data.ZfClient
import io.github.rr5201314.liteschedule.data.ZfParser
import io.github.rr5201314.liteschedule.notify.Notifier
import io.github.rr5201314.liteschedule.widget.TodayWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
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
     * 把所有学期的课表一次性拉回来存档。
     *
     * 教务没有「全部学期」接口：学年下拉是服务端渲染的年份列表，学期只有 3/12/16 三个值，
     * 所以只能按 (学年, 学期) 组合逐个查。这里做了三件事把请求压到最少：
     * 1. 从学号前四位推断入学年份，只从入学那年开始查；
     * 2. 不查晚于当前学期的组合；
     * 3. 已经存档过的学期跳过（force = true 时重查）。
     */
    suspend fun syncAllSemesters(ctx: Context, force: Boolean = false): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val cookie = Store.getCookie(ctx) ?: return@withContext 0 to "还没登录教务"
            runCatching {
                val page = ZfClient.get(cookie, ZfClient.KB_PAGE)
                val surnameYears = ZfClient.parseTermOptions(page).map { it.first }.distinct()

                val currentSnap = Store.loadSnapshot(ctx)
                val current = currentSnap?.let { it.xnm to it.xqm } ?: ZfParser.parseCurrentTerm(page)
                val enrollYear = currentSnap?.studentId?.take(4)?.toIntOrNull()
                    ?: surnameYears.minOrNull()?.toIntOrNull()
                    ?: (java.time.LocalDate.now().year - 6)

                fun termRank(qm: String): Int = when (qm) {
                    "3" -> 0; "12" -> 1; "16" -> 2; else -> 3
                }
                val currentPair = current?.let { (y, q) -> (y.toIntOrNull() ?: 0) to termRank(q) }

                val known = Store.loadArchive(ctx).map { Store.termKey(it.xnm, it.xqm) }.toSet()
                val years = surnameYears.mapNotNull { it.toIntOrNull() }
                    .filter { it >= enrollYear }
                    .filter { y -> currentPair == null || y <= currentPair.first }
                    .ifEmpty { (enrollYear..java.time.LocalDate.now().year).toList() }

                val terms = years.flatMap { y -> listOf("3", "12", "16").map { t -> y.toString() to t } }
                    .filter { (y, t) ->
                        val cur = currentPair ?: return@filter true
                        val yy = y.toIntOrNull() ?: 0
                        val rr = termRank(t)
                        yy < cur.first || (yy == cur.first && rr <= cur.second)
                    }
                    .filter { (y, t) -> force || Store.termKey(y, t) !in known }

                if (terms.isEmpty()) return@runCatching 0 to "所有学期都已存档"

                val periodsCache = java.util.Collections.synchronizedMap(mutableMapOf<String, List<Period>>())
                var saved = 0
                // 每批 3 个并发，够快又不会把教务打疼
                terms.chunked(3).forEach { batch ->
                    val results = batch.map { (y, t) ->
                        async(Dispatchers.IO) { ZfClient.fetchTimetableFor(cookie, y, t, periodsCache) }
                    }.map { it.await() }
                    results.filterNotNull().forEach { snap ->
                        Store.upsertArchive(ctx, snap)
                        saved++
                    }
                }
                val skipped = terms.size - saved
                val tail = if (skipped > 0) "（$skipped 个学期没课）" else ""
                saved to "已保存 $saved 个学期的课表$tail"
            }.getOrElse { 0 to ("拉取失败：" + (it.message ?: "未知错误")) }
        }
}
