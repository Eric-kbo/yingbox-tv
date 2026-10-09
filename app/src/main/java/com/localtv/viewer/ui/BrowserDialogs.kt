package com.localtv.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.localtv.viewer.browser.*
import kotlinx.coroutines.delay

@Composable
fun BrowserFilters(query: BrowserQuery, catalog: MediaCatalog, onApply: (BrowserQuery) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(query) }
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); initial.requestFocus() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(700.dp).fillMaxHeight(.91f).background(Panel, RoundedCornerShape(16.dp)).padding(24.dp)) {
            Text("搜索与筛选", fontSize = 24.sp)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FormField("文件名包含", draft.keyword, "输入关键字，留空查看全部") { draft = draft.copy(keyword = it) }
                FilterSection("排序") {
                    SortOrder.entries.forEachIndexed { index, sort -> TvButton(sort.label, if (index == 0) Modifier.focusRequester(initial) else Modifier, selected = draft.sort == sort) { draft = draft.copy(sort = sort) } }
                }
                FilterSection("文件时间范围") {
                    TvButton("不限", selected = draft.recentDays == null) { draft = draft.copy(recentDays = null) }
                    listOf(7, 30, 365).forEach { days -> TvButton("最近 $days 天", selected = draft.recentDays == days) { draft = draft.copy(recentDays = days, year = null, month = null) } }
                }
                Text("年份", color = TextMuted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("全部年份", selected = draft.year == null) { draft = draft.copy(year = null, month = null) }
                    catalog.years.forEach { year -> TvButton("$year", selected = draft.year == year) { draft = draft.copy(year = year, recentDays = null) } }
                }
                if (draft.year != null) {
                    Text("月份", color = TextMuted, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TvButton("全年", selected = draft.month == null) { draft = draft.copy(month = null) }
                        (1..12).forEach { month -> TvButton("${month}月", selected = draft.month == month) { draft = draft.copy(month = month) } }
                    }
                }
                Text("格式", color = TextMuted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("所有格式", selected = draft.extension == null) { draft = draft.copy(extension = null) }
                    catalog.extensions.forEach { ext -> TvButton(ext.uppercase(), selected = draft.extension == ext) { draft = draft.copy(extension = ext) } }
                }
                Text("筛选当前文件夹；时间由源设备提供。", color = TextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("重置") { draft = BrowserQuery(type = query.type) }
                Spacer(Modifier.weight(1f)); TvButton("取消", onClick = onDismiss)
                TvButton("应用", selected = true) { onApply(draft.copy(keyword = draft.keyword.trim())) }
            }
        }
    }
}

@Composable
private fun FilterSection(label: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun MonthPicker(months: List<MonthJump>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { initial.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(410.dp).heightIn(max = 430.dp).background(Panel, RoundedCornerShape(16.dp)).padding(24.dp)) {
            Text("按月定位", fontSize = 24.sp)
            Spacer(Modifier.height(8.dp)); Text("直接跳到所选月份", fontSize = 12.sp, color = TextMuted)
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(months.size, key = { "${months[it].key.year}:${months[it].key.month}" }) { index ->
                    val month = months[index]
                    TvButton("${month.key.label}      ${month.count} 项", Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(initial) else Modifier)) { onPick(month.firstIndex) }
                }
            }
            Spacer(Modifier.height(14.dp)); TvButton("返回", Modifier.align(Alignment.End), onClick = onDismiss)
        }
    }
}
