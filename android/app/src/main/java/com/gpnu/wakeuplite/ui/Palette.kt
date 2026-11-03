package com.gpnu.wakeuplite.ui

/**
 * 全局配色。
 *
 * 依据：经典粉彩色（#FFB6C1、#98FF98 这类）作为文字在白底上的对比度只有 1.5~2.3:1，
 * 远低于 WCAG AA 的 4.5:1；但同样这些颜色作为“背景 + 深色同色系文字”可以到 10:1 以上。
 * 所以这里每个课程色是一对（浅底 bg + 深字 fg），而不是单纯的饱和色块配白字。
 */
object Palette {

    data class CourseColors(val bg: Int, val fg: Int)

    /** 主色：靛蓝，用于今天高亮、按钮、强调 */
    const val ACCENT = 0xFF3B5BDB.toInt()
    const val ACCENT_SOFT = 0xFFE7ECFC.toInt()

    /** 画布与文字层级 */
    const val CANVAS = 0xFFF6F7FA.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val TEXT_PRIMARY = 0xFF1B1F24.toInt()
    const val TEXT_SECONDARY = 0xFF5B6470.toInt()
    const val TEXT_MUTED = 0xFF98A1AC.toInt()
    const val DIVIDER = 0x0F000000

    private val CARDS = listOf(
        CourseColors(0xFFFBE3E6.toInt(), 0xFF7A2A38.toInt()), // 玫瑰
        CourseColors(0xFFFCEBD2.toInt(), 0xFF6F4418.toInt()), // 暖沙
        CourseColors(0xFFDFF0DF.toInt(), 0xFF25503A.toInt()), // 鼠尾草绿
        CourseColors(0xFFD8EEF1.toInt(), 0xFF1C4A56.toInt()), // 雾青
        CourseColors(0xFFDEE8FB.toInt(), 0xFF22407A.toInt()), // 石板蓝
        CourseColors(0xFFE8E1FA.toInt(), 0xFF43307A.toInt()), // 紫藤
        CourseColors(0xFFFBE2F1.toInt(), 0xFF7A2A5C.toInt()), // 藕粉
        CourseColors(0xFFE9EFD6.toInt(), 0xFF43501F.toInt()), // 橄榄
    )

    fun forCourse(name: String): CourseColors {
        val h = name.hashCode()
        return CARDS[(if (h < 0) -h else h) % CARDS.size]
    }
}
