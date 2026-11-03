package com.gpnu.wakeuplite.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.gpnu.wakeuplite.data.Course
import com.gpnu.wakeuplite.ui.Palette
import com.gpnu.wakeuplite.data.Snapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * 把一整周课表画成位图给桌面小组件用。
 * RemoteViews 不支持自由布局，画布是唯一能做出“真课表”观感的办法。
 */
object WidgetRenderer {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    private val MMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")
    private val DAY_CN = listOf("一", "二", "三", "四", "五", "六", "日")

    fun render(
        ctx: Context,
        snapshot: Snapshot,
        week: Int,
        today: LocalDate,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = v * d

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()

        // 圆角半透明卡片底
        paint.color = 0xF2FFFFFF.toInt()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(16f), dp(16f), paint)

        val pad = dp(12f)
        var y = pad + dp(14f)

        // 顶部：日期 + 周次（字号跟着宽度缩放，小尺寸也不至于顶满）
        val titleSize = min(dp(16f), w * 0.055f)
        val subSize = min(dp(10f), w * 0.034f)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = titleSize
        paint.color = Palette.TEXT_PRIMARY
        canvas.drawText(today.format(DATE_FMT), pad, y, paint)

        y += titleSize + dp(1f)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = subSize
        paint.color = Palette.TEXT_SECONDARY
        val weekText = if (week > 0) "第${week}周 ${weekdayCn(today)}" else weekdayCn(today)
        canvas.drawText(weekText, pad, y, paint)

        y += dp(9f)

        // 日期条
        val leftColW = min(dp(34f), w * 0.14f)
        val gridLeft = pad + leftColW
        val dayW = (w - gridLeft - pad) / 7f

        val monthText = "${today.monthValue}月"
        paint.textSize = dp(8f)
        paint.color = Palette.TEXT_MUTED
        canvas.drawText(monthText, pad, y + dp(9f), paint)

        val monday = if (week > 0) today.with(java.time.DayOfWeek.MONDAY) else null
        // 日期条字号跟着列宽走，窄尺寸下不至于挤成一团
        val dateSize = min(dp(7.5f), dayW * 0.30f)
        val daySize = min(dp(9f), dayW * 0.34f)
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dateSize
            textAlign = Paint.Align.CENTER
            color = Palette.TEXT_MUTED
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = daySize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            color = Palette.TEXT_PRIMARY
        }
        val todayBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
        val todayText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dateSize; textAlign = Paint.Align.CENTER; color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
        }
        val todayDayText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = daySize; textAlign = Paint.Align.CENTER; color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
        }

        val stripH = dateSize + daySize + dp(5f)
        for (i in 0 until 7) {
            val cx = gridLeft + dayW * i + dayW / 2f
            val date = monday?.plusDays(i.toLong())
            val dateBaseline = y + dateSize
            val dayBaseline = y + dateSize + daySize + dp(1f)
            if (date == today) {
                canvas.drawRoundRect(
                    RectF(cx - dayW / 2f + dp(1f), y - dp(1f), cx + dayW / 2f - dp(1f), y + stripH),
                    dp(5f), dp(5f), todayBg
                )
                canvas.drawText(date.format(MMDD), cx, dateBaseline, todayText)
                canvas.drawText("周" + DAY_CN[i], cx, dayBaseline, todayDayText)
            } else {
                if (date != null) canvas.drawText(date.format(MMDD), cx, dateBaseline, datePaint)
                canvas.drawText("周" + DAY_CN[i], cx, dayBaseline, dayPaint)
            }
        }
        y += stripH + dp(3f)

        // 课表网格：只画真正有课的节次范围，行高按剩余空间均分，不硬撑
        val maxSection = (snapshot.courses.maxOfOrNull { it.sectionEnd } ?: 0)
            .coerceAtLeast(1)
            .coerceAtMost(snapshot.maxSection)
        val gridTop = y
        val gridBottom = h - pad
        val rowH = ((gridBottom - gridTop) / maxSection).coerceAtLeast(1f)

        val linePaint = Paint().apply { color = Palette.DIVIDER; strokeWidth = 1f }
        val showLeftText = rowH >= dp(11f)
        val sectionSize = min(dp(8f), rowH * 0.45f)
        val timeSize = min(dp(6f), rowH * 0.30f)
        val showStart = rowH >= dp(19f)
        val showEnd = rowH >= dp(30f)
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sectionSize; textAlign = Paint.Align.CENTER; color = Palette.TEXT_SECONDARY
            typeface = Typeface.DEFAULT_BOLD
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = timeSize; textAlign = Paint.Align.CENTER; color = Palette.TEXT_MUTED
        }

        for (sec in 1..maxSection) {
            val rowTop = gridTop + rowH * (sec - 1)
            if (sec > 1) canvas.drawLine(gridLeft, rowTop, w - pad, rowTop, linePaint)
            if (!showLeftText) {
                val cx = pad + leftColW / 2f
                canvas.drawText("$sec", cx, rowTop + rowH * 0.75f, sectionPaint)
                continue
            }
            val cx = pad + leftColW / 2f
            val p = snapshot.periodOf(sec)
            when {
                showEnd && p != null -> {
                    canvas.drawText("$sec", cx, rowTop + rowH * 0.34f, sectionPaint)
                    canvas.drawText(p.start, cx, rowTop + rowH * 0.66f, timePaint)
                    canvas.drawText(p.end, cx, rowTop + rowH * 0.92f, timePaint)
                }
                showStart && p != null -> {
                    canvas.drawText("$sec", cx, rowTop + rowH * 0.46f, sectionPaint)
                    canvas.drawText(p.start, cx, rowTop + rowH * 0.88f, timePaint)
                }
                else -> canvas.drawText("$sec", cx, rowTop + rowH * 0.72f, sectionPaint)
            }
        }

        // 课程块
        val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (day in 1..7) {
            val dayX = gridLeft + dayW * (day - 1) + dp(1.5f)
            val blockW = dayW - dp(3f)
            val courses = snapshot.courses.filter { it.day == day }
            val clusters = clusterCourses(courses)
            for (cluster in clusters) {
                val active = cluster.filter { week <= 0 || it.activeIn(week) }
                val target: Course?
                val isActive: Boolean
                if (active.isNotEmpty()) {
                    // 本周有课：只画本周的（多门冲突时取节次最多的那门）
                    target = active.maxByOrNull { it.sectionEnd - it.sectionStart }
                    isActive = true
                } else {
                    val upcoming = cluster.filter {
                        week <= 0 || it.weekList.isEmpty() || it.weekList.max() >= week
                    }
                    target = upcoming.minByOrNull { c -> c.weekList.minOfOrNull { kotlin.math.abs(it - week) } ?: Int.MAX_VALUE }
                    isActive = false
                }
                if (target == null) continue

                val span = (target.sectionEnd - target.sectionStart + 1).coerceAtLeast(1)
                val top = gridTop + rowH * (target.sectionStart - 1) + dp(1.5f)
                val bottom = min(gridTop + rowH * (target.sectionStart - 1 + span) - dp(1.5f), gridBottom)
                if (bottom <= top) continue

                blockPaint.color = withAlpha(colorFor(target.name), if (isActive) 255 else 60)
                val rect = RectF(dayX, top, dayX + blockW, bottom)
                canvas.drawRoundRect(rect, dp(4f), dp(4f), blockPaint)

                if (bottom - top < dp(10f)) continue
                canvas.save()
                canvas.clipRect(rect)
                drawBlockText(canvas, target, rect, d, rowH, namePaint, infoPaint, isActive)
                canvas.restore()
            }
        }

        return bmp
    }

    private fun drawBlockText(
        canvas: Canvas,
        c: Course,
        rect: RectF,
        density: Float,
        rowH: Float,
        namePaint: Paint,
        infoPaint: Paint,
        active: Boolean,
    ) {
        fun dp(v: Float) = v * density
        val innerPad = dp(2f)
        val x = rect.left + innerPad
        val maxW = rect.width() - innerPad * 2
        val blockH = rect.height()

        // 字号随块高收缩，块再矮也不会把字挤出卡片
        val baseName = min(dp(10f), max(dp(5f), min(rect.width() * 0.26f, rowH * 0.55f)))
        val nameSize = min(baseName, max(dp(5f), blockH * 0.24f))
        val infoSize = min(dp(6.5f), max(dp(4.5f), min(rect.width() * 0.20f, rowH * 0.42f)))
        namePaint.textSize = nameSize
        infoPaint.textSize = infoSize
        val nameLine = nameSize * 1.15f
        val infoLine = infoSize * 1.20f

        val fg = textColorFor(c.name)
        if (active) {
            namePaint.color = fg
            infoPaint.color = withAlpha(fg, 200)
        } else {
            namePaint.color = withAlpha(fg, 120)
            infoPaint.color = withAlpha(fg, 95)
        }

        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)

        val shortRoom = c.room.replace(Regex("[（(][^）)]*[）)]"), "").trim()
        // 非本周的课不写教室（那周根本不上），改标「非本周」
        val roomText = when {
            !active -> "非本周"
            shortRoom.isNotBlank() -> "@$shortRoom"
            else -> ""
        }
        // 小组件不放周次，信息留给课名和教室；周次在 App 的课程详情里看
        val weekText = ""

        // 先给教室/周次留位置，剩下的高度全给课名：
        // 课一般一次上 2 节以上，块很高，就该用满，而不是固定只给 3 行
        val reserveInfo = when {
            roomText.isBlank() && weekText.isBlank() -> 0f
            blockH >= dp(96f) -> infoLine * 3.2f      // 教室 2 行 + 周次 1 行
            blockH >= dp(64f) -> infoLine * 2.2f      // 教室 2 行
            blockH >= dp(40f) -> infoLine * 1.2f      // 教室 1 行
            else -> 0f
        }
        val nameSpace = (blockH - dp(1.5f) - reserveInfo).coerceAtLeast(nameLine)
        val maxNameLines = (nameSpace / nameLine).toInt().coerceIn(1, 8)
        val nameLines = wrapLines(c.name, maxW, maxNameLines, namePaint)
        var baseline = rect.top + dp(1.5f) + nameSize
        for (line in nameLines) {
            if (baseline > rect.bottom - dp(0.5f)) break
            canvas.drawText(line, x, baseline, namePaint)
            baseline += nameLine
        }

        // 教室：优先压成一行——列窄时自动缩字号，实在放不下才拆两行
        if (roomText.isNotBlank()) {
            val roomLines = (((rect.bottom - baseline) / infoLine).toInt()).coerceIn(0, 2)
            if (roomLines > 0) {
                val fitted = fitSize(roomText, maxW, infoPaint, infoSize, dp(4.2f))
                if (fitted != null) {
                    infoPaint.textSize = fitted
                    canvas.drawText(roomText, x, baseline + fitted * 0.2f, infoPaint)
                    baseline += fitted * 1.25f
                    infoPaint.textSize = infoSize
                } else {
                    for (line in wrapLines(roomText, maxW, roomLines, infoPaint)) {
                        if (baseline + infoSize > rect.bottom) break
                        canvas.drawText(line, x, baseline + infoSize * 0.2f, infoPaint)
                        baseline += infoLine
                    }
                }
            }
        }

        // 周次：同样优先一行放完
        if (weekText.isNotBlank() && baseline + infoSize <= rect.bottom) {
            val fitted = fitSize(weekText, maxW, infoPaint, infoSize, dp(4.2f))
            if (fitted != null) {
                infoPaint.textSize = fitted
                canvas.drawText(weekText, x, baseline + fitted * 0.2f, infoPaint)
                infoPaint.textSize = infoSize
            } else {
                canvas.drawText(ellipsize(weekText, maxW, infoPaint), x, baseline + infoSize * 0.2f, infoPaint)
            }
        }

        canvas.restore()
    }

    /** 按宽度逐字换行，最多 maxLines 行，末行超出加省略号 */
    private fun wrapLines(text: String, maxW: Float, maxLines: Int, paint: Paint): List<String> {
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (ch in text) {
            val candidate = line.toString() + ch
            if (paint.measureText(candidate) > maxW && line.isNotEmpty()) {
                lines.add(line.toString())
                if (lines.size >= maxLines) {
                    // 超出部分丢给省略号
                    val rest = text.substring(text.indexOf(line.toString()) + line.length)
                    lines[lines.size - 1] = ellipsize(lines[lines.size - 1] + rest, maxW, paint)
                    return lines
                }
                line = StringBuilder().append(ch)
            } else {
                line.append(ch)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    /**
     * 在 [minSize, preferred] 之间找一个能把整行塞进 maxW 的字号；
     * 缩到 minSize 还放不下就返回 null，交给换行处理。
     */
    private fun fitSize(text: String, maxW: Float, paint: Paint, preferred: Float, minSize: Float): Float? {
        paint.textSize = preferred
        val full = paint.measureText(text)
        if (full <= maxW) return preferred
        val scaled = preferred * maxW / full
        if (scaled < minSize) return null
        paint.textSize = scaled
        return scaled
    }
    /** 单行超宽时截断并补省略号 */
    private fun ellipsize(text: String, maxW: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxW) return text
        var s = text
        while (s.length > 1 && paint.measureText(s + "…") > maxW) {
            s = s.dropLast(1)
        }
        return s + "…"
    }
/** 逐字换行，超出 maxLines 或 bottom 就停 */
    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        x: Float,
        firstBaseline: Float,
        maxW: Float,
        maxLines: Int,
        paint: Paint,
        lineH: Float,
        bottom: Float,
    ): Float {
        var line = StringBuilder()
        var y = firstBaseline
        var lines = 0
        for (ch in text) {
            val candidate = line.toString() + ch
            if (paint.measureText(candidate) > maxW && line.isNotEmpty()) {
                canvas.drawText(line.toString(), x, y, paint)
                lines++
                if (lines >= maxLines || y + lineH > bottom) return y
                y += lineH
                line = StringBuilder().append(ch)
            } else {
                line.append(ch)
            }
        }
        if (line.isNotEmpty() && lines < maxLines && y <= bottom) {
            canvas.drawText(line.toString(), x, y, paint)
            y += lineH
        }
        return y
    }

    private fun colorFor(name: String): Int = Palette.forCourse(name).bg

    private fun textColorFor(name: String): Int = Palette.forCourse(name).fg

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun weekdayCn(today: LocalDate): String =
        "周" + DAY_CN[today.dayOfWeek.value - 1]

    /** 同一天里节次相互重叠的课聚成一组 */
    private fun clusterCourses(courses: List<Course>): List<List<Course>> {
        val sorted = courses.sortedBy { it.sectionStart }
        val clusters = ArrayList<MutableList<Course>>()
        for (c in sorted) {
            val hits = clusters.indices.filter { i ->
                clusters[i].any { c.sectionStart <= it.sectionEnd && it.sectionStart <= c.sectionEnd }
            }
            if (hits.isEmpty()) {
                clusters.add(mutableListOf(c))
            } else {
                val first = hits.first()
                clusters[first].add(c)
                for (i in hits.drop(1)) {
                    clusters[first].addAll(clusters[i])
                    clusters[i].clear()
                }
            }
        }
        return clusters.filter { it.isNotEmpty() }
    }
}
