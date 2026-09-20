package com.facedemo.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.facedemo.app.core.ImageUtil
import com.facedemo.app.core.ThresholdStore
import com.facedemo.app.vm.AppViewModel
import com.facedemo.app.vm.UiState
import java.io.File
import java.util.Locale

/**
 * 名单页 —— 只管名单与人脸库，不碰相机：
 *
 *   * 导入名单（粘贴 / 选 CSV-TXT 文件，自动识别 GBK / UTF-16 编码）
 *   * 加人、改班级/备注（这一栏不限制内容，写「科任老师」这类说明也行）
 *   * 点名字 → 直接跳到「录入」页并选中他
 *   * 删除：可选「只移出名单」或「连照片一起删」；删掉的人不会被自动入册，可一键恢复
 *   * 人脸库：看每个人有几张照片、逐张删除、重载
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterPage(
    vm: AppViewModel,
    state: UiState,
    onEnrollPerson: (String, String) -> Unit
) {
    val context = LocalContext.current
    var showPaste by remember { mutableStateOf(false) }
    var showAddStudent by remember { mutableStateOf(false) }
    var openPerson by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var pendingOnly by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var classFilter by remember { mutableStateOf("") }

    val rosterPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) vm.importRosterFile(uri) }
    val photoImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> -> vm.importPhotos(uris, process = false) }
    val batchEnroller = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> -> vm.importPhotos(uris, process = true) }

    val people = remember(state.rosterRevision, state.galleryPhotos) { vm.people() }
    val removedNames = remember(state.rosterRevision) { vm.removedNames() }
    val gallery = remember(state.galleryPhotos) { vm.galleryPhotos() }
    val classes = remember(state.rosterRevision) { people.map { it.klass }.distinct().sorted() }
    /** 个人阈值：设过的人在这里，列表里标出来 */
    val personThresholds = remember(state.rosterRevision, state.configRevision) { vm.personThresholds() }

    // 筛选: 只看待录入 × 班级 × 关键字（姓名/学号/班级都能搜）
    val filtered = remember(people, pendingOnly, keyword, classFilter) {
        val key = keyword.trim()
        people.filter { person ->
            (!pendingOnly || !person.enrolled) &&
                (classFilter.isEmpty() || person.klass == classFilter) &&
                (key.isEmpty() ||
                    person.name.contains(key, ignoreCase = true) ||
                    person.studentId.contains(key, ignoreCase = true) ||
                    person.klass.contains(key, ignoreCase = true))
        }
    }
    val photoCount = gallery.values.sumOf { it.size }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ------------------------------------------------ 名单
        item {
            SectionCard(
                title = "名单 ${people.size} 人 · ${classes.size} 个班",
                icon = "users"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 四个按钮平分整行宽度（文字放不下会自动缩字，不换行、不切字）
                    FillRow {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { showPaste = true }
                        ) { BtnContent("clipboard-list", "粘贴导入") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                rosterPicker.launch(
                                    arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "*/*")
                                )
                            }
                        ) { BtnContent("file-csv", "选名单文件") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { showAddStudent = true }
                        ) { BtnContent("user-plus", "加一个人") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { vm.reloadRoster() }
                        ) { BtnContent("rotate", "重载名单") }
                    }

                    KeyValueRow("名单文件", vm.config.rosterFile.name, icon = "folder-open")
                    KeyValueRow("已录入人脸", "${state.enrolledCount} 人", icon = "user-check")
                    KeyValueRow(
                        "待录入", "${state.pendingCount} 人",
                        valueColor = if (state.pendingCount == 0) null else Color(0xFFB35C00),
                        icon = "hourglass-half"
                    )
                    if (classes.isNotEmpty()) {
                        Text(
                            "现有的班级/备注: " + classes.joinToString("、"),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 手动删掉的人不会自动入册 —— 给个恢复入口
                    if (removedNames.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "已手动删除 ${removedNames.size} 人（不会被自动入册）: " +
                                    removedNames.take(10).joinToString("、") +
                                    if (removedNames.size > 10) " …" else "",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { vm.restoreAutoEnroll() }
                            ) { BtnContent("rotate", "恢复自动入册（按照片重新进名单）", size = 13.dp) }
                        }
                    }

                    FillRow {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = state.pendingCount > 0,
                            onClick = {
                                val next = vm.pendingPeople().firstOrNull()
                                if (next != null) onEnrollPerson(next.name, next.klass)
                            }
                        ) { BtnContent("camera", "去录入下一位", size = 14.dp) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { pendingOnly = !pendingOnly }
                        ) {
                            BtnContent(if (pendingOnly) "check" else null, "只看待录入", size = 14.dp)
                        }
                    }

                    // ---- 筛选 ----
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("搜索") },
                        leadingIcon = { FaIcon("magnifying-glass", size = 16.dp) },
                        trailingIcon = {
                            if (keyword.isNotEmpty()) {
                                TextButton(onClick = { keyword = "" }) {
                                    FaIcon("xmark", size = 14.dp)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (classes.isNotEmpty()) {
                        Text(
                            "按班级筛选" + if (classFilter.isEmpty()) "" else "（当前：$classFilter）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OptionButton("全部", classFilter.isEmpty()) { classFilter = "" }
                            classes.forEach { klass ->
                                OptionButton(klass, classFilter == klass) { classFilter = klass }
                            }
                        }
                    }
                    Text(
                        "共 ${people.size} 人，筛选后 ${filtered.size} 人" +
                            (if (pendingOnly) "（只看待录入）" else "") +
                            " · 点名字 → 跳到「录入」页并选中他",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val visible = filtered.sortedWith(compareBy({ it.enrolled }, { it.klass }, { it.name }))
                    val shown = if (showAll) visible else visible.take(12)
                    if (shown.isEmpty()) {
                        val emptyText = when {
                            people.isEmpty() -> "名单里还没有人。可以先「粘贴导入」或「加一个人」。"
                            keyword.isNotEmpty() || classFilter.isNotEmpty() ->
                                "没有符合筛选条件的人。"
                            else -> "名单里的人都录入好了"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (people.isNotEmpty() && keyword.isEmpty() && classFilter.isEmpty()) {
                                FaIcon("circle-check", size = 14.dp, tint = Color(0xFF25C55E))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                emptyText,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    shown.forEach { person ->
                        val selected = person.name == state.enrollName
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else Color.Transparent
                                )
                                .clickable { onEnrollPerson(person.name, person.klass) }
                                .padding(vertical = 6.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FaIcon(
                                if (person.enrolled) "circle-check" else "circle-xmark",
                                size = 14.dp,
                                tint = if (person.enrolled) Color(0xFF25C55E)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(person.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    buildString {
                                        append(person.klass.ifBlank { "未分班" })
                                        if (person.studentId.isNotBlank()) append(" · ${person.studentId}")
                                        append(" · ")
                                        append(if (person.enrolled) "已录 ${person.photos} 张" else "待录入")
                                        personThresholds[person.name]?.let {
                                            append(" · 阈值 ")
                                            append(String.format(Locale.US, "%.3f", it))
                                        }
                                    },
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = if (personThresholds.containsKey(person.name))
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 这三个动作**只用图标**（相机=去录入、笔=改资料、垃圾桶=删除）：
                            // 原来带文字的按钮在一行里放三个时，文字会被挤掉看不见
                            CircleIconButton(
                                icon = "camera",
                                tint = MaterialTheme.colorScheme.primary,
                                description = "录入 ${person.name}",
                                onClick = { onEnrollPerson(person.name, person.klass) }
                            )
                            Spacer(Modifier.width(6.dp))
                            CircleIconButton(
                                icon = "pen-to-square",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                description = "修改 ${person.name} 的资料",
                                onClick = { openPerson = person.name }
                            )
                            Spacer(Modifier.width(6.dp))
                            CircleIconButton(
                                icon = "trash-can",
                                tint = MaterialTheme.colorScheme.error,
                                description = "删除 ${person.name}",
                                onClick = { deleteTarget = person.name }
                            )
                        }
                    }
                    if (!showAll && visible.size > shown.size) {
                        TextButton(onClick = { showAll = true }) {
                            BtnContent("plus", "显示全部 ${visible.size} 人", size = 13.dp)
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ 人脸库
        item {
            SectionCard(
                title = "人脸库 ${gallery.size} 人 · $photoCount 张",
                icon = "images"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FillRow {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                photoImporter.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) { BtnContent("download", "导入照片", size = 12.dp) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                batchEnroller.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) { BtnContent("wand-magic-sparkles", "导入并抠像", size = 12.dp) }
                        // v1.0.2：把采集到的人像整体打包带走（zip，含人像清单.csv）
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val zip = vm.exportPhotosZip()
                                if (zip != null) shareFile(context, zip, "application/zip")
                            }
                        ) { BtnContent("file-zipper", "导出人像包", size = 12.dp) }
                    }
                    Text(
                        "「原样」= 直接把照片拷进人脸库（不抠像、不换底，文件名当姓名）；\n" +
                            "「抠像成一寸照」= 按设置里的规格（一寸照 + 背景色）处理，人多时更适合。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (gallery.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("人脸库为空，去「录入」页拍一张", fontSize = 13.sp) }
                    }
                    gallery.entries.sortedBy { it.key }.take(60).forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FaIcon("user-check", size = 14.dp, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.key, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${entry.value.size} 张 · " + entry.value.joinToString("、"),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 人脸库里的「管理」也改成纯图标（笔），和管理对话框一一对应
                            CircleIconButton(
                                icon = "pen-to-square",
                                tint = MaterialTheme.colorScheme.primary,
                                description = "管理 ${entry.key} 的照片",
                                onClick = { openPerson = entry.key }
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.size(4.dp)) }
    }

    // ------------------------------------------------ 粘贴名单
    if (showPaste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("粘贴名单") },
            text = {
                Column {
                    Text(
                        "支持这些格式（表头自动识别，不要求列顺序；没有表头也不挑内容）:\n" +
                            "班级,姓名,学号,备注\n一班,张三,2026001,\n科任老师,张三,2026001\n" +
                            "张三,2026001\n2026001,张三\n1,张三",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("名单内容") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(top = 10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importRosterText(text, "")
                    showPaste = false
                }) { BtnContent("check", "导入") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("取消") } }
        )
    }

    // ------------------------------------------------ 加人
    if (showAddStudent) {
        var newName by remember { mutableStateOf("") }
        var newClass by remember { mutableStateOf("") }
        var newId by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddStudent = false },
            title = { Text("添加一个人") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newClass, onValueChange = { newClass = it },
                        label = { Text("班级 / 备注") },
                        placeholder = { Text("例如：一班、科任老师") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newId, onValueChange = { newId = it },
                        label = { Text("学号") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.addStudent(newName, newClass, newId)
                    showAddStudent = false
                }) { BtnContent("check", "添加") }
            },
            dismissButton = { TextButton(onClick = { showAddStudent = false }) { Text("取消") } }
        )
    }

    // ------------------------------------------------ 管理某个人
    openPerson?.let { person ->
        val files = gallery[person] ?: emptyList()
        val info = people.firstOrNull { it.name == person }
        var editClass by remember(person) { mutableStateOf(info?.klass.orEmpty()) }
        var editId by remember(person) { mutableStateOf(info?.studentId.orEmpty()) }
        // 个人阈值：文本框留空 = 用全局阈值；填了就只对这个人生效
        val globalNow = remember(person, state.configRevision) { vm.globalThreshold() }
        var editThreshold by remember(person, state.configRevision) {
            mutableStateOf(
                vm.personThreshold(person)?.let { String.format(Locale.US, "%.2f", it) } ?: ""
            )
        }
        AlertDialog(
            onDismissRequest = { openPerson = null },
            title = { Text(person) },
            text = {
                // 整个内容可滚动（照片 + 资料），避免内容一多被对话框高度裁掉
                Column(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // ---------------- 上部：原图（名片大小） ----------------
                    // 一人一张就够了：只把**第一张**原图放大显示（名片大小），
                    // 多于一张时给一个「只保留这一张」的清理按钮。
                    val photoDir = remember { File(vm.photoDirPath()) }
                    val first = files.firstOrNull()
                    if (first == null) {
                        Text(
                            "还没有照片，点下面「去录入」拍一张一寸照。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val big = remember(first, state.galleryPhotos) {
                            ImageUtil.decodeFile(File(photoDir, first), 720)
                        }
                        val sizeKb = remember(first, state.galleryPhotos) {
                            (File(photoDir, first).length() / 1024).toInt()
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            // 一寸照 25:35 比例，按名片大小显示（96×134dp）
                            Box(
                                Modifier
                                    .width(96.dp)
                                    .height(134.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(8.dp)
                                    ),
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
                                    FaIcon(
                                        "image",
                                        size = 26.dp,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    first,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    "$sizeKb KB" + if (files.size > 1) " · 另有 ${files.size - 1} 张" else "",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { vm.deletePhoto(first) }) {
                                    BtnContent("trash-can", "删除这张", size = 13.dp)
                                }
                                // v1.0.2：单独把这张照片存到系统相册（Android 10+ 不用任何权限）
                                TextButton(onClick = { vm.savePhotoToLocal(first) }) {
                                    BtnContent("download", "保存到本地", size = 13.dp)
                                }
                                if (files.size > 1) {
                                    // 重复录入会攒下多张，这里一键只留第一张
                                    TextButton(onClick = {
                                        files.drop(1).forEach { vm.deletePhoto(it) }
                                    }) {
                                        BtnContent("eraser", "只保留这一张", size = 13.dp)
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )

                    // ---------------- 下半部分：资料 ----------------
                    OutlinedTextField(
                        value = editClass, onValueChange = { editClass = it },
                        label = { Text("班级 / 备注（自由填写）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editId, onValueChange = { editId = it },
                        label = { Text("学号") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    OutlinedTextField(
                        value = editThreshold,
                        onValueChange = { input ->
                            // 只允许数字和小数点，长度也限制一下
                            editThreshold = input.filter { it.isDigit() || it == '.' }.take(5)
                        },
                        label = { Text("个人阈值（留空＝用全局）") },
                        placeholder = {
                            Text("全局 " + String.format(Locale.US, "%.3f", globalNow))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    val typed = editThreshold.trim()
                    val parsed = ThresholdStore.parseThreshold(typed)
                    val thresholdHint = when {
                        typed.isEmpty() ->
                            "留空：这个人和大家一样用全局阈值 " +
                                String.format(Locale.US, "%.3f", globalNow) + "。"
                        parsed == null ->
                            "阈值要填 ${ThresholdStore.MIN}~${ThresholdStore.MAX} 之间的小数，" +
                                "例如 0.55；填别的会按“留空”处理。"
                        else ->
                            "填了 " + String.format(Locale.US, "%.3f", parsed) +
                                "：只对 $person 生效（越大越严，越小越松），其余人不受影响。"
                    }
                    val thresholdBad = typed.isNotEmpty() && parsed == null
                    Row(
                        Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (thresholdBad) {
                            FaIcon(
                                "triangle-exclamation",
                                size = 13.dp,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            thresholdHint,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = if (thresholdBad) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FillRow(modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                vm.setStudentClass(person, editClass.ifBlank { "未分班" })
                                vm.setStudentId(person, editId)
                                vm.setPersonThreshold(person, ThresholdStore.parseThreshold(editThreshold))
                                openPerson = null
                            }
                        ) { BtnContent("check", "保存", size = 13.dp) }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onEnrollPerson(person, editClass)
                                openPerson = null
                            }
                        ) { BtnContent("camera", "去录入", size = 13.dp) }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        onClick = {
                            deleteTarget = person
                            openPerson = null
                        }
                    ) {
                        FaIcon("trash-can", size = 14.dp, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("删除这个人", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { openPerson = null }) { Text("关闭") } }
        )
    }

    // ------------------------------------------------ 删除确认
    deleteTarget?.let { person ->
        val photos = gallery[person]?.size ?: 0
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「$person」") },
            text = {
                Text(
                    if (photos > 0) {
                        "这个人名下有 $photos 张照片。\n\n" +
                            "· 只移出名单：名单里删掉他，照片留着；以后重新导入名单或重新录入，他会自动恢复。\n" +
                            "· 连照片一起删：名单和 $photos 张照片全部删除，不能撤销。"
                    } else {
                        "名单里没有他的照片。\n\n" +
                            "· 只移出名单：从名单里删掉他，之后不会再被自动入册；\n" +
                            "· 连照片一起删：名单和可能存在的照片都会清掉。"
                    },
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                // 两个按钮**等宽、一行放得下**（文字放不下会自动缩字号），不再换行、不再错位
                FillRow {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (state.enrollName == person) vm.setEnrollTarget("", "")
                            vm.removeStudent(person, deletePhotos = false)
                            deleteTarget = null
                        }
                    ) { BtnContent("user-minus", "只移出名单", size = 13.dp) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (state.enrollName == person) vm.setEnrollTarget("", "")
                            vm.removeStudent(person, deletePhotos = true)
                            deleteTarget = null
                        }
                    ) { BtnContent("trash-can", "连照片一起删", size = 13.dp) }
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
