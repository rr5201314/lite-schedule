package io.github.rr5201314.liteschedule.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.rr5201314.liteschedule.data.SessionKeeper
import io.github.rr5201314.liteschedule.data.Store
import java.util.concurrent.TimeUnit

/**
 * 登录态保活：每 2 小时用现有 Cookie 访问一次教务，合并服务端下发的新 Cookie。
 * 这样只要用户还在用这台手机，登录态就不会因为长时间不访问而过期。
 */
class SessionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (Store.getCookie(applicationContext) == null) return Result.success()
        val ok = runCatching { SessionKeeper.checkNow(applicationContext) }.getOrDefault(false)
        return if (ok) Result.success() else Result.success()
    }

    companion object {
        private const val PERIODIC = "wakeup-lite-session"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<SessionWorker>(2, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}