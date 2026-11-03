package com.gpnu.wakeuplite.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gpnu.wakeuplite.data.Store
import java.util.concurrent.TimeUnit

/**
 * 定时同步：拉最新课表，和本地快照比对，有变化就发通知。
 * 实际逻辑在 SyncRunner，界面上的手动同步也走同一份。
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (Store.getCookie(applicationContext) == null) {
            return Result.failure(workDataOf("reason" to "尚未登录教务"))
        }

        val outcome = SyncRunner.run(applicationContext)
        if (outcome.ok) {
            return Result.success(
                workDataOf("changes" to outcome.changes.size, "courses" to outcome.courseCount)
            )
        }
        return if (outcome.message.contains("失效") || outcome.message.contains("登录")) {
            // 登录态过期需要用户重新登录，重试没有意义
            Result.failure(workDataOf("reason" to outcome.message))
        } else {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "wakeup-lite-sync"
        private const val ONCE = "wakeup-lite-sync-once"

        fun schedulePeriodic(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun syncNow(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
