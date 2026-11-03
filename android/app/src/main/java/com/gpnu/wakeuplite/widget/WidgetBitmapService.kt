package com.gpnu.wakeuplite.widget

import android.widget.RemoteViews
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViewsService
import com.gpnu.wakeuplite.R
import com.gpnu.wakeuplite.data.Store
import java.time.LocalDate

/**
 * 整周课表位图的数据源。
 *
 * 位图是「一整张长图」，组件视口只露出一部分，用户可以在组件里上下滚动看完整周。
 * ListView 集合控件负责滚动，这里只返回一个 item（那张长图）。
 */
class WidgetBitmapService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widthPx = intent.getIntExtra(EXTRA_WIDTH_PX, 900).coerceIn(200, 2400)
        return WidgetBitmapFactory(applicationContext, widthPx)
    }

    companion object {
        const val EXTRA_WIDTH_PX = "width_px"
    }
}

private class WidgetBitmapFactory(
    private val ctx: Context,
    private val widthPx: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var bitmap: Bitmap? = null

    override fun onCreate() = Unit

    override fun onDestroy() {
        bitmap?.recycle()
        bitmap = null
    }

    override fun getCount(): Int = if (bitmap == null) 0 else 1
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = 0L
    override fun hasStableIds(): Boolean = false
    override fun getLoadingView(): RemoteViews? = null

    override fun onDataSetChanged() {
        val snapshot = Store.loadSnapshot(ctx) ?: return
        val week = Store.currentWeek(ctx).coerceAtLeast(1)
        val density = ctx.resources.displayMetrics.density

        // 行高按固定 dp 算：组件高度不够时不全塞进去，靠滚动看剩下的
        val contentHdp = 88f + snapshot.maxSection * 52f
        val heightPx = (contentHdp * density).toInt()

        bitmap = WidgetRenderer.render(ctx, snapshot, week, LocalDate.now(), widthPx, heightPx)

        // 调试用：把渲染结果落一份到应用目录，方便 adb 拉出来看
        runCatching {
            java.io.File(ctx.filesDir, "widget_preview.png").outputStream().use {
                bitmap?.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_bitmap_item)
        bitmap?.let { views.setImageViewBitmap(R.id.widget_bitmap, it) }
        // 点一下打开 App
        views.setOnClickFillInIntent(R.id.widget_bitmap, Intent())
        return views
    }
}
