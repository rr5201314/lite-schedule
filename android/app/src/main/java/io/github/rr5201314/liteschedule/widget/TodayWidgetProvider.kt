package io.github.rr5201314.liteschedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import io.github.rr5201314.liteschedule.MainActivity
import io.github.rr5201314.liteschedule.R
import io.github.rr5201314.liteschedule.data.Store

/**
 * 桌面小组件：整周课表（可在组件内上下滚动）。
 *
 * RemoteViews 不能摆任意控件，所以整张课表仍由 WidgetRenderer 画成一张长图；
 * 外面套一个 ListView 集合控件，图比组件视口高，用户就能在桌面上下滚动看完整周。
 *
 * 刷新时机：同步完成、App 回前台、校准周次、跨天/改时间/换时区/开机、系统每 30 分钟。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateOne(ctx, mgr, it) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_BOOT_COMPLETED -> refreshAll(ctx)
        }
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context,
        mgr: AppWidgetManager,
        id: Int,
        newOptions: android.os.Bundle,
    ) {
        // 用户拖动改变组件宽度后重画
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        updateOne(ctx, mgr, id)
    }

    companion object {

        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodayWidgetProvider::class.java))
            ids.forEach { updateOne(ctx, mgr, it) }
        }

        private fun updateOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            // 没有课表就先空着，等同步完刷新
            Store.loadSnapshot(ctx) ?: return

            // 只按宽度决定画布：高度由内容算（一整张长图），超出视口的部分靠滚动
            val options = mgr.getAppWidgetOptions(id)
            val density = ctx.resources.displayMetrics.density
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)

            var sizesW = 0
            if (Build.VERSION.SDK_INT >= 31) {
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                sizes?.maxByOrNull { it.height }?.let { sizesW = it.width.toInt() }
            }

            val wDp = when {
                sizesW > 0 -> sizesW
                minW > 0 -> minW
                maxW > 0 -> maxW
                else -> 250
            }
            val wPx = (wDp * density).toInt().coerceIn(200, 2400)
            android.util.Log.i("LiteScheduleWidget", "id=$id width=${wPx}px")

            val views = RemoteViews(ctx.packageName, R.layout.widget_today)
            val serviceIntent = Intent(ctx, WidgetBitmapService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(WidgetBitmapService.EXTRA_WIDTH_PX, wPx)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_scroll_list, serviceIntent)
            views.setEmptyView(R.id.widget_scroll_list, R.id.widget_empty)

            // 点一下打开 App
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_scroll_list, open)

            mgr.updateAppWidget(id, views)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_scroll_list)
        }
    }
}
