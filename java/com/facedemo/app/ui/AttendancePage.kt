package com.facedemo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.facedemo.app.core.AttendanceRecord
import com.facedemo.app.core.ImageUtil
import com.facedemo.app.core.Roster
import com.facedemo.app.vm.AppViewModel
import com.facedemo.app.vm.UiState
import java.util.Locale

data class BoardStudent(
    val name: String,
    val studentId: String,
    val note: String,
    val present: Boolean,
    val checkin: String,
    val checkout: String,
    val manual: Boolean,
    /** v1.0.2：一天多次打卡的「签到-签退」明细，例如 `09:00:00-09:05:00 · 12:00:00-` */
    val span: String = "",
    val sessionCount: Int = 0
)

data class BoardGroup(
    val klass: String,
    val total: Int,
    val present: Int,
    val rate: Double,
    val students: List<BoardStudent>
)

data class BoardData(
    val date: String,
    val kind: String,
    val total: Int,
    val present: Int,
    val absent: Int,
    val rate: Double,
    val records: Int,
    val groups: List<BoardGroup>,
    /** v1.0.2：今天一共配出多少段「签到-签退」 */
    val sessions: Int = 0
)

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.toBoardData(): BoardData {
    if (isEmpty()) return BoardData("", "签到", 0, 0, 0, 0.0, 0, emptyList())
    val summary = (this["summary"] as? Map<String, Any?>) ?: emptyMap()
    val groups = ((this["groups"] as? List<Map<String, Any?>>) ?: emptyList()).map { group ->
        val students = ((group["students"] as? List<Map<String, Any?>>) ?: emptyList()).map { item ->
            BoardStudent(
                name = item["name"] as? String ?: "",
                studentId = item["studentId"] as? String ?: "",
                note = item["note"] as? String ?: "",
                present = item["present"] == true,
                checkin = item["checkin"] as? String ?: "",
                checkout = item["checkout"] as? String ?: "",
                manual = item["manual"] == true,
                span = item["span"] as? String ?: "",
                sessionCount = (item["sessionCount"] as? Int) ?: 0
            )
        }
        BoardGroup(
            klass = group["klass"] as? String ?: "未分班",
            total = (group["total"] as? Int) ?: students.size,
            present = (group["present"] as? Int) ?: students.count { it.present },
            rate = (group["rate"] as? Double) ?: 0.0,
            students = students
        )
    }
    return BoardData(
        date = this["date"] as? String ?: "",
        kind = this["kind"] as? String ?: "签到",
        total = (summary["total"] as? Int) ?: 0,
        present = (summary["present"] as? Int) ?: 0,
        absent = (summary["absent"] as? Int) ?: 0,
        rate = (summary["rate"] as? Double) ?: 0.0,
        records = (summary["records"] as? Int) ?: 0,
        groups = groups,
        sessions = (summary["sessions"] as? Int) ?: 0
    )
}

/**
 * 签到页: 全员到场看板 + 手动补签 + 导出点名表。
 * 对应 Python 版网页端的“签到”页。
 *
 * 排版说明（v1.0.0 修）：原来整页是一个固定 Column，中间塞了一个 weight(1f) 的列表、
 * 底下再挂一张“打卡设置”卡片 —— 手机上这几块会互相挤压，文字就挤成一团。
 * 现在整页就是**一个 LazyColumn**，所有内容按顺序作为 item 排列，超出一屏就滚动，
 * 谁也不抢谁的空间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendancePage(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    var klassFilter by remember { mutableStateOf("") }
    // 状态筛选：all = 应到（名单里所有人）、present = 已到、absent = 未到
    var statusFilter by remember { mutableStateOf("all") }
    // v1.0.7：「管理」对话框看的人
    var managePerson by remember { mutableStateOf<String?>(null) }

    // v1.0.7：**把 state.attendanceKind 也作为 key** —— 以前只认 attendanceRevision，
    // 切模式（签到/签退）不会让 board 重算，于是 board.kind 是旧的，
    // 补签用的就是旧类型（"签退切回签到后马上点补签，补的还是签退"）。
    val board = remember(state.attendanceRevision, klassFilter, state.rosterRevision, state.attendanceKind) {
        vm.board(klassFilter).toBoardData()
    }
    @Suppress("UNCHECKED_CAST")
    val classes = remember(state.attendanceRevision, state.rosterRevision) {
        vm.rosterSummary()["classes"] as? List<String> ?: emptyList()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------- 模式与操作 ----------------
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 用 FillRow：按钮平分整行宽度、一行放得下，文字放不下会自动缩字
                FillRow {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setAttendanceKind("签到") },
                        enabled = state.attendanceKind != "签到"
                    ) { BtnContent("right-to-bracket", "签到") }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { vm.setAttendanceKind("签退") },
                        enabled = state.attendanceKind != "签退"
                    ) { BtnContent("right-from-bracket", "签退") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { vm.onAttendanceChanged() }
                    ) { BtnContent("arrows-rotate", "刷新看板") }
                }
                // v1.0.2：导出分成三块 —— 今日点名表 / 全部日期原始记录 / 全部日期配对明细
                FillRow {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val text = vm.boardCsv(klassFilter)
                            shareText(context, text, "点名表_${board.date}.csv", "text/csv")
                        }
                    ) { BtnContent("file-export", "导出今日", size = 12.dp) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            shareText(context, vm.allAttendanceCsv(), "全部打卡记录.csv", "text/csv")
                        }
                    ) { BtnContent("calendar-days", "全部记录", size = 12.dp) }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            shareText(context, vm.allSessionsCsv(), "考勤明细_签到签退.csv", "text/csv")
                        }
                    ) { BtnContent("clock", "签到签退明细", size = 12.dp) }
                }
            }
        }

        // ---------------- 概览 ----------------
        item {
            SectionCard(title = "${board.date} 到场看板 · ${board.kind}", icon = "clipboard-check") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox("应到", board.total.toString(), MaterialTheme.colorScheme.onSurface)
                        StatBox("已到", board.present.toString(), Color(0xFF25C55E))
                        StatBox("未到", board.absent.toString(), Color(0xFFEF5350))
                        StatBox(
                            "出席率",
                            String.format(Locale.US, "%.0f%%", board.rate * 100),
                            MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "今日打卡 ${board.records} 条" +
                            (if (board.sessions > 0) "（${board.sessions} 段签到-签退）" else "") +
                            " · 每个人名字后面是「补签到／补签退」（跟着当前模式走）和「管理」（看他的资料和当天记录）；\n" +
                            "「全部记录」= 所有日期的打卡流水；「签到签退明细」= 每人每段一行（只签到就是 `时间-`）",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------------- 筛选：状态（应到／已到／未到）+ 班级 ----------------
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "筛选",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 状态筛选：应到 = 名单里所有人；已到 = 今天有签到记录的；未到 = 还没有记录的
                FillRow {
                    listOf(
                        "all" to "应到",
                        "present" to "已到",
                        "absent" to "未到"
                    ).forEach { (value, label) ->
                        OutlinedButton(onClick = { statusFilter = value }) {
                            BtnContent(
                                if (statusFilter == value) "check" else null,
                                label,
                                size = 14.dp
                            )
                        }
                    }
                }
                if (classes.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { klassFilter = "" }) {
                            BtnContent(
                                if (klassFilter.isEmpty()) "check" else null,
                                "全部班级",
                                size = 14.dp
                            )
                        }
                        classes.forEach { klass ->
                            OutlinedButton(onClick = { klassFilter = klass }) {
                                BtnContent(
                                    if (klassFilter == klass) "check" else null,
                                    klass,
                                    size = 14.dp
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 各班名单 ----------------
        // 按状态筛选后再渲染：某个班筛完没人就不显示那张卡
        val visibleGroups = board.groups.mapNotNull { group ->
            val matched = group.students.filter { student ->
                when (statusFilter) {
                    "present" -> student.present
                    "absent" -> !student.present
                    else -> true
                }
            }
            if (matched.isEmpty()) null else group to matched
        }
        items(visibleGroups, key = { it.first.klass }) { (group, matched) ->
            SectionCard(
                title = "${group.klass}  ${group.present}/${group.total}",
                icon = "users",
                trailing = {
                    Text(
                        String.format(Locale.US, "%.0f%%", group.rate * 100),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    matched.forEach { student ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FaIcon(
                                if (student.present) "circle-check" else "circle-xmark",
                                size = 15.dp,
                                tint = if (student.present) Color(0xFF25C55E)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    student.name +
                                        if (student.studentId.isNotEmpty()) "  ${student.studentId}" else "",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                val detail = buildString {
                                    // v1.0.2：一天可以多次打卡，优先显示「签到-签退」明细
                                    if (!student.present) {
                                        append("未到")
                                    } else if (student.span.isNotEmpty()) {
                                        append(if (student.sessionCount > 1) "已到 ${student.sessionCount} 次：" else "已到 ")
                                        append(student.span)
                                    } else {
                                        append("已到 ${student.checkin}")
                                    }
                                    if (student.manual) append(" · 手动补签")
                                    if (student.note.isNotEmpty() && student.note != "手动补签") {
                                        append(" · ${student.note}")
                                    }
                                }
                                Text(
                                    detail,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = if (student.present) Color(0xFF25C55E)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // v1.0.6：去掉「撤销」，只留「补签」。
                            // 撤销那条路径删掉的理由：它删的是"这个人的这类记录**全部**"，
                            // 而且按钮按"有没有签到"显示、跟当前模式对不上（详见 v1.0.6 记录）。
                            //
                            // v1.0.7：
                            //   * 补签传**实时模式** `state.attendanceKind`（不是缓存过的 board.kind），
                            //     否则"签退切回签到后马上点补签"会补成签退；
                            //   * 「管理」按钮（v1.0.7 新增）：看这个人的基本信息和今天的记录，
                            //     已到/未到都有，所以不再受 present 限制。
                            //
                            // v1.0.8：补签按钮**不再按"有没有签到"显示**（那会导致"签到+签退"这段完整
                            // 记录之后按钮消失，想补下一段就补不了），改成按**当前模式的规则**：
                            //   * 签到模式：一直显示 —— 一天可以打多次卡，下午再补一条早上的签到是合理的；
                            //     要是还没到间隔时间，AttendanceStore 会拒绝并说明"还要等多久"。
                            //   * 签退模式：只有"有开着的签到"时才显示 —— 没签到本来就不许签退，
                            //     显示一个点了必被拒的按钮没意义。
                            val canManualSign = if (state.attendanceKind == "签退") {
                                vm.canCheckOut(student.name)
                            } else true
                            if (canManualSign) {
                                TextButton(onClick = { vm.manualSign(student.name, state.attendanceKind) }) {
                                    BtnContent(
                                        "circle-check",
                                        if (state.attendanceKind == "签退") "补签退" else "补签到",
                                        size = 13.dp
                                    )
                                }
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { managePerson = student.name }) {
                                BtnContent("circle-info", "管理", size = 13.dp)
                            }
                        }
                    }
                }
            }
        }

        // v1.0.7：下面这段是**上一版放错位置**留下的（原本想写在这里的对话框），
        // 真正的调用已经挪到 LazyColumn 外面了，这里只留个记号提醒别再往里塞 @Composable。

        if (board.groups.isNotEmpty() && visibleGroups.isEmpty()) {
            item {
                SectionCard(title = "筛选结果", icon = "clipboard-list") {
                    val allHere = statusFilter == "absent"
                    val line = (if (klassFilter.isEmpty()) "" else "${klassFilter} · ") +
                        when (statusFilter) {
                            "present" -> "这个范围里今天还没有人签到。"
                            "absent" -> "这个范围里的人都到了"
                            else -> "没有符合条件的人。"
                        }
                    Row(
                        Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (allHere) {
                            FaIcon("circle-check", size = 15.dp, tint = Color(0xFF25C55E))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            line,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (board.groups.isEmpty()) {
            item {
                SectionCard(title = "到场看板", icon = "clipboard-list") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "看板为空：名单里还没有人，也没有人脸库照片。\n" +
                                "先到「录入」页导入名单或录入人脸。",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---------------- 打卡设置搬去设置页了（v1.0.3）----------------
        // 这一页只留一句提示 + 今日文件位置；开关、间隔、名单外处理都在「设置 → 打卡」里。
        item {
            SectionCard(title = "打卡设置", icon = "sliders") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "打卡的开关和间隔都在「设置 → 打卡」里（不常改，不占这一页的地方）。\n" +
                            "现在一天可以打任意多次卡：签到 → 签退 之后可以马上再签到；" +
                            "只有同一种类型连着来（比如连着两次签到）才会被间隔挡住。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KeyValueRow(
                        "今日文件",
                        java.io.File(vm.config.logsDir, "attendance_${board.date}.csv").name,
                        icon = "folder-open"
                    )
                }
            }
        }

        item { Spacer(Modifier.width(4.dp)) }
    }

    // v1.0.7：人员信息 + 当天记录（放在 LazyColumn 外面：它的 content 是普通 lambda，
    // 里面不能直接调 @Composable）
    managePerson?.let { person ->
        PersonTodayDialog(vm = vm, state = state, name = person, onClose = { managePerson = null })
    }
}

/**
 * v1.0.7 新增：签到页每个人的「管理」对话框 —— 上面是基本信息和照片，
 * 下面是**今天的全部记录**（时间 / 类型 / 相似度 / 来源）+ 签到-签退配对汇总。
 *
 * 为什么加它：撤销按钮去掉之后，看板上只剩"补签"，想核对某个人今天到底打了几次、
 * 有没有签退、是不是手动补的，就没有地方看了。这个框就是给现场核对的。
 */
@Composable
fun PersonTodayDialog(vm: AppViewModel, state: UiState, name: String, onClose: () -> Unit) {
    val info = remember(state.rosterRevision, state.galleryPhotos) {
        vm.people().firstOrNull { it.name == name }
    }
    val photos = remember(state.galleryPhotos) { vm.galleryPhotos()[name] ?: emptyList() }
    val records = remember(state.attendanceRevision, name) { vm.recordsOf(name) }
    val sessions = remember(state.attendanceRevision, name) { vm.sessionsOf(name) }
    val threshold = remember(state.configRevision, state.rosterRevision) { vm.personThreshold(name) }
    val photoDir = remember { java.io.File(vm.photoDirPath()) }
    // v1.0.9：点某条记录 → 先记下来，弹确认框，确认后才真删
    var pendingDelete by remember(name) { mutableStateOf<AttendanceRecord?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(name) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ---------------- 基本信息 ----------------
                Row(verticalAlignment = Alignment.Top) {
                    val first = photos.firstOrNull()
                    val big = remember(first, state.galleryPhotos) {
                        if (first == null) null else ImageUtil.decodeFile(java.io.File(photoDir, first), 480)
                    }
                    Box(
                        Modifier
                            .width(88.dp)
                            .height(123.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (big != null) {
                            Image(
                                bitmap = big.asImageBitmap(),
                                contentDescription = first,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            FaIcon("image", size = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine("班级", info?.klass.orEmpty().ifEmpty { Roster.UNASSIGNED })
                        InfoLine("学号", info?.studentId.orEmpty().ifEmpty { "-" })
                        if (!info?.note.isNullOrEmpty()) InfoLine("备注", info.note)
                        InfoLine("人脸库", if (photos.isEmpty()) "没有照片" else "${photos.size} 张")
                        InfoLine(
                            "个人阈值",
                            threshold?.let { String.format(Locale.US, "%.2f（专属）", it) } ?: "跟全局一样"
                        )
                        InfoLine(
                            "当前状态",
                            if (records.any { it.kind == "签到" && !it.note.startsWith("撤销") }) "已签到" else "未签到",
                            highlight = records.any { it.kind == "签到" && !it.note.startsWith("撤销") }
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )

                // ---------------- 打卡时段（配对） ----------------
                Text("打卡时段", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (sessions.isEmpty()) "今天还没有打卡"
                    else sessions.joinToString(" · ") { it.text },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(10.dp))

                // ---------------- 今天的原始记录 ----------------
                Text("今天记录（${records.size} 条）", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (records.isEmpty()) {
                    Text(
                        "还没有记录。可以在看板上点「补签」，或者让人站到镜头前自动打卡。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "点某一条可以直接删掉它（会先弹一次确认）",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    records.forEach { record ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                // v1.0.9：整行可点 = 删除这条（不做单独的删除按钮，按用户要求）
                                .clickable { pendingDelete = record }
                                .padding(vertical = 6.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FaIcon(
                                if (record.kind == "签退") "right-from-bracket" else "right-to-bracket",
                                size = 13.dp,
                                tint = if (record.kind == "签退") MaterialTheme.colorScheme.onSurfaceVariant
                                else Color(0xFF25C55E)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(record.clock, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(record.kind, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                buildString {
                                    append(
                                        record.similarity?.let { String.format(Locale.US, "%.3f", it) } ?: "-"
                                    )
                                    if (record.source.isNotEmpty()) append(" · ${record.source}")
                                    if (record.note.isNotEmpty()) append(" · ${record.note}")
                                },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            FaIcon(
                                "trash-can",
                                size = 12.dp,
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )

    // 删除确认（点记录时弹出；确认后才真的删）
    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记录？") },
            text = {
                Text(
                    buildString {
                        append("${record.clock}  ${record.kind}\n")
                        append(record.name)
                        if (record.similarity != null) {
                            append("  ·  相似度 ")
                            append(String.format(Locale.US, "%.3f", record.similarity))
                        }
                        if (record.source.isNotEmpty()) append("  ·  ${record.source}")
                        append("\n\n只删这一条，其它记录不动。删完不能撤销。")
                    },
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRecord(record.id)
                    pendingDelete = null
                }) { BtnContent("trash-can", "删除", size = 13.dp) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 对话框里的一行"标签：值" */
@Composable
private fun InfoLine(label: String, value: String, highlight: Boolean = false) {
    Row {
        Text(
            "$label：",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            fontSize = 11.sp,
            fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal,
            color = if (highlight) Color(0xFF25C55E) else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun StatBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
