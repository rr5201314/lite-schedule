package io.github.rr5201314.liteschedule

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.rr5201314.liteschedule.data.CalendarResolver
import io.github.rr5201314.liteschedule.data.CampusFeed
import io.github.rr5201314.liteschedule.data.Course
import io.github.rr5201314.liteschedule.BuildConfig
import io.github.rr5201314.liteschedule.data.SessionKeeper
import io.github.rr5201314.liteschedule.data.UpdateChecker
import io.github.rr5201314.liteschedule.data.UpdateInfo
import io.github.rr5201314.liteschedule.data.Snapshot
import io.github.rr5201314.liteschedule.data.Store
import io.github.rr5201314.liteschedule.login.LoginActivity
import io.github.rr5201314.liteschedule.notify.Notifier
import io.github.rr5201314.liteschedule.sync.SessionWorker
import io.github.rr5201314.liteschedule.sync.SyncRunner
import io.github.rr5201314.liteschedule.sync.SyncWorker
import io.github.rr5201314.liteschedule.ui.Palette
import io.github.rr5201314.liteschedule.widget.TodayWidgetProvider
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        SyncWorker.schedulePeriodic(this)
        SessionWorker.schedule(this)
        if (Store.getCookie(this) != null) SyncWorker.syncNow(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(Palette.ACCENT))) {
                Surface(Modifier.fillMaxSize(), color = Color(Palette.CANVAS)) { AppScreen() }
            }
        }
    }
}

private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val ROW_HEIGHT = 42.dp
private val LEFT_COL_WIDTH = 32.dp
private val MMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")
private const val MAX_WEEKS = 30

@Composable
private fun AppScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(Store.loadSnapshot(ctx)) }
    var reload by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showCalibrate by remember { mutableStateOf(false) }
    var showNotices by remember { mutableStateOf(false) }
    var showPortals by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf(CampusFeed.loadCached(ctx)) }
    var tab by remember { mutableIntStateOf(0) }
    var noticesRefreshing by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var week1 by remember { mutableStateOf(Store.getWeek1(ctx)) }
    var sessionState by remember { mutableStateOf(Store.getSessionState(ctx)) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val today = LocalDate.now()

    val initialPage = (Store.currentWeek(ctx).coerceAtLeast(1) - 1).coerceIn(0, MAX_WEEKS - 1)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MAX_WEEKS })
    val currentWeek = pagerState.currentPage + 1

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = Store.loadSnapshot(ctx)
                week1 = Store.getWeek1(ctx)
                TodayWidgetProvider.refreshAll(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reload) { snapshot = Store.loadSnapshot(ctx) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = Store.loadSnapshot(ctx)
            week1 = Store.getWeek1(ctx)
            kotlinx.coroutines.delay(2000)
        }
    }

    // 登录态：启动时探一次并把服务端新 Cookie 合并回来
    LaunchedEffect(Unit) {
        if (Store.getCookie(ctx) != null) {
            SessionKeeper.checkNow(ctx)
            sessionState = Store.getSessionState(ctx)
        }
    }

    // 检查更新：6 小时内只查一次，发现新版本弹窗
    LaunchedEffect(Unit) {
        UpdateChecker.checkForUpdate(ctx)?.let { updateInfo = it }
    }

    // 校园公告：启动一次；进入公告页再拉；前台每 10 分钟自动刷新
    suspend fun refreshNotices() {
        noticesRefreshing = true
        notices = CampusFeed.fetchNotices(ctx)
        noticesRefreshing = false
    }
    LaunchedEffect(Unit) { refreshNotices() }
    LaunchedEffect(tab) { if (tab == 1) refreshNotices() }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10 * 60 * 1000L)
            refreshNotices()
        }
    }

    // 还没校准过第 1 周时，用教务部公开通知自己算（不需要登录）
    LaunchedEffect(Unit) {
        if (Store.getWeek1(ctx) <= 0L) {
            val snap = Store.loadSnapshot(ctx)
            if (snap != null && snap.xnm.isNotEmpty()) {
                CalendarResolver.resolve(snap.xnm, snap.xqm)?.let { r ->
                    Store.setWeek1(ctx, r.week1EpochDay)
                    Store.setWeek1Term(ctx, "${snap.xnm}-${snap.xqm}")
                    week1 = r.week1EpochDay
                }
            }
        }
    }

    // 第 1 周从“未知”变成“已知”（比如同步时自动校准）后，自动跳到当前周
    LaunchedEffect(week1) {
        val cw = Store.currentWeek(ctx)
        if (week1 > 0 && cw > 0) {
            val target = (cw - 1).coerceIn(0, MAX_WEEKS - 1)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        }
    }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(ctx, "登录成功，正在同步", Toast.LENGTH_SHORT).show()
            SyncWorker.syncNow(ctx)
        }
    }

    val monday = if (week1 > 0) LocalDate.ofEpochDay(week1).plusWeeks((currentWeek - 1).toLong()) else null

    val runSync: () -> Unit = {
        if (Store.getCookie(ctx) == null) {
            Toast.makeText(ctx, "还没登录教务，先点「登录教务」", Toast.LENGTH_LONG).show()
        } else if (!syncing) {
            syncing = true
            scope.launch {
                val outcome = SyncRunner.run(ctx)
                syncing = false
                snapshot = Store.loadSnapshot(ctx)
                Toast.makeText(ctx, outcome.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding()
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> {
                    Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(20.dp),
                color = Color(Palette.SURFACE),
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "第 $currentWeek 周",
                                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                color = Color(Palette.TEXT_PRIMARY)
                            )
                            Text(
                                if (syncing) "正在同步…"
                                else if (sessionState == "expired") "登录已过期，点 ⋮ 重新登录"
                                else "2026-2027 第一学期" + (monday?.let { " · " + it.format(MMDD) + " 起" } ?: ""),
                                fontSize = 10.sp,
                                color = when {
                                    syncing -> Color(Palette.ACCENT)
                                    sessionState == "expired" -> Color(0xFFB3261E)
                                    else -> Color(Palette.TEXT_MUTED)
                                }
                            )
                        }
                        // 翻到别的周时给一个回本周的入口
                        val nowWeek = Store.currentWeek(ctx)
                        if (nowWeek > 0 && pagerState.currentPage != (nowWeek - 1).coerceIn(0, MAX_WEEKS - 1)) {
                            Text(
                                "回到本周",
                                fontSize = 10.sp,
                                color = Color(Palette.ACCENT),
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                                    .clickableNoRipple {
                                        scope.launch {
                                            pagerState.animateScrollToPage((nowWeek - 1).coerceIn(0, MAX_WEEKS - 1))
                                        }
                                    }
                            )
                        }
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp).size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "↻", fontSize = 18.sp, color = Color(Palette.ACCENT),
                                modifier = Modifier.padding(8.dp).clickableNoRipple { runSync() }
                            )
                        }
                        Box {
                            Text(
                                "⋮", fontSize = 20.sp, color = Color(Palette.TEXT_PRIMARY),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    .clickableNoRipple { menuOpen = true }
                            )
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("登录教务") }, onClick = {
                                    menuOpen = false
                                    loginLauncher.launch(Intent(ctx, LoginActivity::class.java))
                                })
                                DropdownMenuItem(text = { Text("立即同步") }, onClick = {
                                    menuOpen = false
                                    runSync()
                                })
                                DropdownMenuItem(text = { Text(if (checkingUpdate) "检查中…" else "检查更新") }, onClick = {
                                    menuOpen = false
                                    if (!checkingUpdate) {
                                        checkingUpdate = true
                                        scope.launch {
                                            val info = UpdateChecker.checkForUpdate(ctx, force = true)
                                            checkingUpdate = false
                                            if (info != null) {
                                                updateInfo = info
                                            } else {
                                                Toast.makeText(ctx, "已是最新版本 v" + UpdateChecker.currentVersion(), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                })
                                DropdownMenuItem(text = { Text("校准当前周") }, onClick = {
                                    menuOpen = false; showCalibrate = true
                                })
                            }
                        }
                    }
    
                    Spacer(Modifier.height(2.dp))
                    DateStrip(monday, today)
    
                    // 最新公告一行，点开看列表
                    val latestNotice = notices.firstOrNull()
                    if (latestNotice != null) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(top = 3.dp, bottom = 1.dp)
                                .clickableNoRipple { tab = 1 },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(latestNotice.source, fontSize = 9.sp, color = Color(Palette.ACCENT), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                latestNotice.title,
                                fontSize = 9.sp,
                                color = Color(Palette.TEXT_SECONDARY),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                CampusFeed.relativeDate(latestNotice.date),
                                fontSize = 9.sp,
                                color = Color(Palette.TEXT_MUTED)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
    
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        val pageWeek = page + 1
                        val pageMonday = if (week1 > 0) {
                            LocalDate.ofEpochDay(week1).plusWeeks((pageWeek - 1).toLong())
                        } else null
                        WeekGrid(snapshot, pageWeek, pageMonday, today, Modifier.fillMaxSize()) { course -> detailCourse = course }
                    }
                }
            }
                }
                1 -> NoticesPane(
                    notices = notices,
                    refreshing = noticesRefreshing,
                    onRefresh = { scope.launch { refreshNotices() } },
                    onOpen = { url -> openUrl(ctx, url) }
                )
                2 -> PortalsPane { url -> openUrl(ctx, url) }
            }
        }
        Spacer(Modifier.height(6.dp))
        BottomNav(current = tab, onSelect = { tab = it })
    }
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text("发现新版本 ${info.tag}", fontSize = 17.sp) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "当前 v${BuildConfig.VERSION_NAME}  →  最新 v${info.version}",
                        fontSize = 12.sp,
                        color = Color(Palette.TEXT_SECONDARY)
                    )
                    if (info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            info.notes.take(600),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = Color(Palette.TEXT_PRIMARY)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    openUrl(ctx, info.apkUrl.ifBlank { info.releaseUrl })
                    updateInfo = null
                }) { Text("去下载") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        Store.setSkippedVersion(ctx, info.version)
                        updateInfo = null
                    }) { Text("跳过此版") }
                    TextButton(onClick = { updateInfo = null }) { Text("以后再说") }
                }
            }
        )
    }

    detailCourse?.let { course ->
        AlertDialog(
            onDismissRequest = { detailCourse = null },
            title = { Text(course.name, fontSize = 17.sp, lineHeight = 23.sp) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    DetailRow("教师", listOfNotNull(course.teacher.takeIf { it.isNotBlank() }, course.teacherTitle.takeIf { it.isNotBlank() }).joinToString(" "))
                    DetailRow(
                        "时间",
                        "${DAY_NAMES.getOrElse(course.day - 1) { "" }} 第${course.sectionStart}-${course.sectionEnd}节" +
                            (course.timeLabel.takeIf { it.isNotBlank() }?.let { "  $it" } ?: "")
                    )
                    DetailRow("地点", listOfNotNull(
                        course.campus.takeIf { it.isNotBlank() },
                        course.building.takeIf { it.isNotBlank() },
                        course.room.takeIf { it.isNotBlank() },
                    ).joinToString(" · "))
                    DetailRow("周次", course.weeksLabel)
                    DetailRow("学分", course.credit)
                    DetailRow("课程类别", course.type)
                    DetailRow("课程性质", course.courseNature)
                    DetailRow("考核方式", course.assessMode)
                    DetailRow("考试方式", course.examMode)
                    DetailRow("上课方式", course.teachMode)
                    DetailRow("学时", listOfNotNull(
                        course.totalHours.takeIf { it.isNotBlank() }?.let { "$it 学时" },
                        course.hourCompose.takeIf { it.isNotBlank() },
                    ).joinToString("  "))
                    DetailRow("教学班", course.classInfo)
                    DetailRow("班级", course.classCompose)
                    DetailRow("选课人数", course.students)
                    DetailRow("课程号", course.courseNo)
                    DetailRow("场地类别", course.siteType)
                }
            },
            confirmButton = { TextButton(onClick = { detailCourse = null }) { Text("关闭") } }
        )
    }
    if (showNotices) {
        AlertDialog(
            onDismissRequest = { showNotices = false },
            title = { Text("校园公告") },
            text = {
                if (notices.isEmpty()) {
                    Text("暂时没拉到公告，联网后从菜单再打开一次试试", fontSize = 12.sp)
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(notices) { n ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickableNoRipple {
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url)))
                                        }
                                        showNotices = false
                                    }
                                    .padding(vertical = 7.dp)
                            ) {
                                Text(n.title, fontSize = 12.sp, color = Color(Palette.TEXT_PRIMARY))
                                Text(
                                    "${n.date} · ${CampusFeed.relativeDate(n.date)}",
                                    fontSize = 10.sp, color = Color(Palette.TEXT_MUTED)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNotices = false }) { Text("关闭") } }
        )
    }

    if (showPortals) {
        AlertDialog(
            onDismissRequest = { showPortals = false },
            title = { Text("常用入口") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(CampusFeed.links) { link ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickableNoRipple {
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                                    }
                                    showPortals = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(link.name, fontSize = 13.sp, color = Color(Palette.TEXT_PRIMARY))
                            if (link.note.isNotBlank()) {
                                Text(link.note, fontSize = 10.sp, color = Color(Palette.TEXT_MUTED))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPortals = false }) { Text("关闭") } }
        )
    }

    if (showCalibrate) {
        var input by remember { mutableStateOf("$currentWeek") }
        AlertDialog(
            onDismissRequest = { showCalibrate = false },
            title = { Text("现在是第几周？") },
            text = {
                Column {
                    Text("按教务课表页顶部的周次填，程序会倒推第 1 周周一。", fontSize = 12.sp)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter { ch -> ch.isDigit() }.take(2) },
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = input.toIntOrNull()
                    if (n != null && n in 1..MAX_WEEKS) {
                        val mon = LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks((n - 1).toLong())
                        Store.setWeek1(ctx, mon.toEpochDay())
                        snapshot?.let { Store.setWeek1Term(ctx, "${it.xnm}-${it.xqm}") }
                        week1 = mon.toEpochDay()
                        scope.launch { pagerState.scrollToPage(n - 1) }
                        TodayWidgetProvider.refreshAll(ctx)
                        reload++
                    } else {
                        Toast.makeText(ctx, "填 1 到 $MAX_WEEKS 之间的数字", Toast.LENGTH_SHORT).show()
                    }
                    showCalibrate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCalibrate = false }) { Text("取消") } }
        )
    }
}

/** 日期条：上面几号，下面星期，今天整块主色 */
@Composable
private fun DateStrip(monday: LocalDate?, today: LocalDate) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            if (monday != null) "${monday.monthValue}月" else "",
            Modifier.width(LEFT_COL_WIDTH),
            fontSize = 9.sp, color = Color(Palette.TEXT_MUTED)
        )
        for (i in 0 until 7) {
            val date = monday?.plusDays(i.toLong())
            val isToday = date == today
            Column(
                Modifier.weight(1f)
                    .background(
                        if (isToday) Color(Palette.ACCENT) else Color.Transparent,
                        RoundedCornerShape(7.dp)
                    )
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    date?.format(MMDD) ?: "  ",
                    fontSize = 10.sp,
                    color = if (isToday) Color.White else Color(Palette.TEXT_MUTED),
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    DAY_NAMES[i],
                    fontSize = 11.sp,
                    color = if (isToday) Color.White else Color(Palette.TEXT_PRIMARY),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** 课表网格：左侧节次时间，右侧 7 天，课程按节次跨度合并。
 *  行高按可用高度自适应：小屏不挤，大屏不留白。 */
@Composable
private fun WeekGrid(
    snapshot: Snapshot?,
    week: Int,
    monday: LocalDate?,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onCourseClick: (Course) -> Unit = {},
) {
    val maxSection = snapshot?.maxSection ?: 12
    BoxWithConstraints(modifier) {
        val rowHeight = (maxHeight / maxSection.toFloat()).coerceAtLeast(34.dp)
        val gridHeight: Dp = rowHeight * maxSection.toFloat()

        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(Modifier.width(LEFT_COL_WIDTH)) {
                for (sec in 1..maxSection) {
                    Column(
                        Modifier.height(rowHeight),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "$sec", fontSize = 11.sp, lineHeight = 12.sp,
                            color = Color(Palette.TEXT_SECONDARY), fontWeight = FontWeight.Medium
                        )
                        val p = snapshot?.periodOf(sec)
                        Text(p?.start ?: "", fontSize = 7.5.sp, lineHeight = 8.5.sp, color = Color(Palette.TEXT_MUTED))
                    }
                }
            }

            for (day in 1..7) {
                Box(Modifier.weight(1f).height(gridHeight)) {
                    Column {
                        for (sec in 1..maxSection) {
                            Box(
                                Modifier.fillMaxWidth().height(rowHeight)
                                    .drawBehind {
                                        drawLine(
                                            Color(Palette.DIVIDER),
                                            Offset(0f, size.height),
                                            Offset(size.width, size.height),
                                            1f
                                        )
                                    }
                            )
                        }
                    }
                    val dayCourses = snapshot?.courses
                        ?.filter { it.day == day }
                        ?.sortedBy { it.sectionStart }
                        ?: emptyList()
                    val pastWeek = { c: Course ->
                        week > 0 && c.weekList.isNotEmpty() && c.weekList.max() < week
                    }
                    val clusters = clusterCourses(dayCourses)
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val dayWidth = maxWidth
                        for (cluster in clusters) {
                            val active = cluster.filter { week <= 0 || it.activeIn(week) }
                            if (active.isNotEmpty()) {
                                for (p in layoutCourses(active)) ActiveCard(p, dayWidth, rowHeight, onCourseClick)
                            } else {
                                val nearest = cluster
                                    .filter { week <= 0 || it.weekList.isEmpty() || !pastWeek(it) }
                                    .minByOrNull { weekDistance(it, week) }
                                    ?: continue
                                FadedCard(nearest, rowHeight, onCourseClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class Placed(val course: Course, val column: Int, val colCount: Int)

/** 本周要上的课：浅底 + 同色系深字 */
@Composable
private fun ActiveCard(p: Placed, dayWidth: Dp, rowHeight: Dp, onClick: (Course) -> Unit) {
    val c = p.course
    val colors = Palette.forCourse(c.name)
    val colWidth = dayWidth / p.colCount
    val span = (c.sectionEnd - c.sectionStart + 1).coerceAtLeast(1)
    val condensed = p.colCount >= 2
    Box(
        Modifier
            .offset(x = colWidth * p.column, y = rowHeight * (c.sectionStart - 1).toFloat())
            .width(colWidth)
            .height(rowHeight * span.toFloat())
            .padding(1.dp)
            .clickableNoRipple { onClick(c) }
            .background(Color(colors.bg), RoundedCornerShape(7.dp))
            .padding(horizontal = 3.dp, vertical = 3.dp)
    ) {
        Column {
            Text(
                c.name,
                fontSize = if (condensed) 9.sp else 10.sp,
                lineHeight = if (condensed) 10.sp else 11.5.sp,
                color = Color(colors.fg), fontWeight = FontWeight.SemiBold, maxLines = 6
            )
            if (!condensed && c.room.isNotBlank()) {
                Text(
                    "@${c.room}", fontSize = if (colWidth < 46.dp) 6.5.sp else 8.sp,
                    lineHeight = if (colWidth < 46.dp) 7.5.sp else 9.sp,
                    color = Color(colors.fg).copy(alpha = 0.72f), maxLines = 2
                )
            }
            if (!condensed && colWidth >= 46.dp && c.weeksLabel.isNotBlank()) {
                Text(
                    c.weeksLabel, fontSize = 7.sp, lineHeight = 8.sp,
                    color = Color(colors.fg).copy(alpha = 0.55f), maxLines = 2
                )
            }
        }
    }
}

/** 本周不上、但还没开始的课：更淡的同色底，只露课名 */
@Composable
private fun FadedCard(c: Course, rowHeight: Dp, onClick: (Course) -> Unit) {
    val colors = Palette.forCourse(c.name)
    val span = (c.sectionEnd - c.sectionStart + 1).coerceAtLeast(1)
    Box(
        Modifier
            .offset(y = rowHeight * (c.sectionStart - 1).toFloat())
            .fillMaxWidth()
            .height(rowHeight * span.toFloat())
            .padding(1.dp)
            .clickableNoRipple { onClick(c) }
            .background(Color(colors.bg).copy(alpha = 0.45f), RoundedCornerShape(7.dp))
            .padding(horizontal = 3.dp, vertical = 3.dp)
    ) {
        Column {
            Text(
                c.name, fontSize = 8.5.sp, lineHeight = 10.sp,
                color = Color(colors.fg).copy(alpha = 0.5f), fontWeight = FontWeight.Medium, maxLines = 5
            )
            if (rowHeight * span.toFloat() > 44.dp) {
                Text(
                    "非本周", fontSize = 6.5.sp, lineHeight = 8.sp,
                    color = Color(colors.fg).copy(alpha = 0.45f), maxLines = 1
                )
            }
        }
    }
}
/** 当前周和这门课最近的上课周差几周 */
private fun weekDistance(c: Course, week: Int): Int =
    c.weekList.minOfOrNull { kotlin.math.abs(it - week) } ?: Int.MAX_VALUE

/** 同一天里节次相互重叠的课聚成组，一片区域只画一门 */
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

/** 同一天里时间重叠的课并排放 */
private fun layoutCourses(courses: List<Course>): List<Placed> {
    if (courses.isEmpty()) return emptyList()
    val assigned = ArrayList<Pair<Course, Int>>()
    for (c in courses) {
        val used = HashSet<Int>()
        for ((other, col) in assigned) {
            val overlap = c.sectionStart <= other.sectionEnd && other.sectionStart <= c.sectionEnd
            if (overlap) used.add(col)
        }
        var col = 0
        while (used.contains(col)) col++
        assigned.add(c to col)
    }
    return assigned.map { (c, col) ->
        val group = assigned.filter { (o, _) ->
            c.sectionStart <= o.sectionEnd && o.sectionStart <= c.sectionEnd
        }
        Placed(c, col, (group.maxOf { it.second } + 1).coerceAtLeast(1))
    }
}

/**
 * 小图标点击，去掉水波纹。
 * 必须用 rememberUpdatedState 包一层：pointerInput(Unit) 只捕获首次组合时的 lambda，
 * 否则像分页这种依赖当前状态的点击会一直按旧值计算。
 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val current = androidx.compose.runtime.rememberUpdatedState(onClick)
    return this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures { current.value() }
        }
    )
}

/** 打开外部链接 */
private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** 底部三个 tab：课表 / 公告 / 官网 */
@Composable
private fun BottomNav(current: Int, onSelect: (Int) -> Unit) {
    val items = listOf("课表", "公告", "官网")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, label ->
            val selected = current == index
            Box(
                Modifier.weight(1f)
                    .clickableNoRipple { onSelect(index) }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = if (selected) Color(Palette.ACCENT) else Color(Palette.TEXT_SECONDARY),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .width(if (selected) 18.dp else 0.dp)
                            .height(3.dp)
                            .background(Color(Palette.ACCENT), RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

/** 公告页：多源合并，卡片式资讯流 + 分页（参考 CampBrief 的每日资讯版式） */
@Composable
private fun NoticesPane(
    notices: List<CampusFeed.Notice>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var filter by remember { mutableStateOf("全部") }
    var page by remember { mutableIntStateOf(1) }
    val pageSize = 10

    val filters = listOf("全部") + CampusFeed.sources
    val shown = if (filter == "全部") notices else notices.filter { it.source == filter }
    val pageCount = ((shown.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safePage = page.coerceIn(1, pageCount)
    val pageItems = shown.drop((safePage - 1) * pageSize).take(pageSize)

    // 换来源或数据刷新后回到第一页
    LaunchedEffect(filter, shown.size) { page = 1 }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        color = Color(Palette.SURFACE),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text("校园资讯", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(Palette.TEXT_PRIMARY))
                    Text(
                        if (refreshing) "正在拉取最新内容…"
                        else "六源合并 · 进入本页自动刷新",
                        fontSize = 10.sp,
                        color = if (refreshing) Color(Palette.ACCENT) else Color(Palette.TEXT_MUTED)
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        "刷新", fontSize = 11.sp, color = Color(Palette.ACCENT),
                        modifier = Modifier.padding(8.dp).clickableNoRipple { onRefresh() }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filters.forEach { f ->
                    val selected = filter == f
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (selected) Color(Palette.ACCENT) else Color(Palette.ACCENT_SOFT),
                        modifier = Modifier.clickableNoRipple { filter = f }
                    ) {
                        Text(
                            f,
                            fontSize = 11.sp,
                            color = if (selected) Color.White else Color(Palette.ACCENT),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                Text("暂时没拉到内容，联网后点右上角刷新", fontSize = 12.sp, color = Color(Palette.TEXT_MUTED))
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pageItems) { n -> NoticeCard(n, onOpen) }
                }
                Spacer(Modifier.height(6.dp))
                PagerBar(
                    page = safePage,
                    pageCount = pageCount,
                    onPrev = { if (safePage > 1) page = safePage - 1 },
                    onNext = { if (safePage < pageCount) page = safePage + 1 },
                )
            }
        }
    }
}

/** 底部翻页条：上一页 / 第 x 页 共 y 页 / 下一页 */
@Composable
private fun PagerBar(page: Int, pageCount: Int, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            "上一页", fontSize = 11.sp,
            color = if (page > 1) Color(Palette.ACCENT) else Color(Palette.TEXT_MUTED),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                .clickableNoRipple { onPrev() }
        )
        Text(
            "$page / $pageCount",
            fontSize = 11.sp,
            color = Color(Palette.TEXT_SECONDARY),
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        Text(
            "下一页", fontSize = 11.sp,
            color = if (page < pageCount) Color(Palette.ACCENT) else Color(Palette.TEXT_MUTED),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                .clickableNoRipple { onNext() }
        )
    }
}
/** 单条资讯卡片 */
@Composable
private fun NoticeCard(n: CampusFeed.Notice, onOpen: (String) -> Unit) {
    val rel = CampusFeed.relativeDate(n.date)
    Surface(
        modifier = Modifier.fillMaxWidth().clickableNoRipple { onOpen(n.url) },
        shape = RoundedCornerShape(14.dp),
        color = Color(Palette.SURFACE),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x14000000)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row {
                Text(
                    n.title,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(Palette.TEXT_PRIMARY),
                    modifier = Modifier.weight(1f)
                )
                if (rel.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(999.dp), color = Color(Palette.ACCENT_SOFT)) {
                        Text(
                            rel, fontSize = 9.sp, color = Color(Palette.ACCENT),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(999.dp), color = Color(0x0F000000)) {
                    Text(
                        n.source, fontSize = 9.5.sp, color = Color(Palette.TEXT_SECONDARY),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(n.date, fontSize = 10.sp, color = Color(Palette.TEXT_MUTED))
                Spacer(Modifier.weight(1f))
                Text("阅读原文 ›", fontSize = 10.5.sp, color = Color(Palette.ACCENT))
            }
            if (n.excerpt.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    n.excerpt, fontSize = 11.sp, lineHeight = 16.sp,
                    color = Color(Palette.TEXT_SECONDARY), maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
/** 官网入口页：每个入口一张卡片，写清用途 */
@Composable
private fun PortalsPane(onOpen: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        color = Color(Palette.SURFACE),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                "官网入口", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = Color(Palette.TEXT_PRIMARY),
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Text(
                "常用系统快捷跳转", fontSize = 10.sp, color = Color(Palette.TEXT_MUTED),
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(CampusFeed.links) { link ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickableNoRipple { onOpen(link.url) },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(Palette.ACCENT_SOFT).copy(alpha = 0.55f),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(link.name, fontSize = 14.sp, color = Color(Palette.TEXT_PRIMARY), fontWeight = FontWeight.Medium)
                                if (link.note.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(link.note, fontSize = 10.5.sp, lineHeight = 14.sp, color = Color(Palette.TEXT_SECONDARY))
                                }
                            }
                            Text("›", fontSize = 18.sp, color = Color(Palette.TEXT_MUTED))
                        }
                    }
                }
            }
        }
    }
}

/** 详情页的一行：左边标签，右边内容，空值不显示 */
@Composable
private fun DetailRow(label: String, value: String, muted: Boolean = false) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            fontSize = 11.sp,
            color = Color(Palette.TEXT_MUTED),
            modifier = Modifier.width(62.dp)
        )
        Text(
            value,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = if (muted) Color(Palette.TEXT_MUTED) else Color(Palette.TEXT_PRIMARY),
            modifier = Modifier.weight(1f)
        )
    }
}