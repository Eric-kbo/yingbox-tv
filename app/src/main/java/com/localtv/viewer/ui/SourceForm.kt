package com.localtv.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.localtv.viewer.MainViewModel
import com.localtv.viewer.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SourceForm(existing: Source?, model: MainViewModel, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(existing?.kind ?: SourceKind.SMB) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var domain by remember { mutableStateOf(existing?.domain.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); initial.requestFocus() }
    fun source() = SourceValidation.normalized(name, kind, address, username, password, domain, existing?.id)
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(690.dp).fillMaxHeight(.91f).background(Panel, RoundedCornerShape(24.dp)).padding(26.dp)) {
            Text(if (existing == null) "添加媒体源" else "修改媒体源", fontSize = 25.sp)
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("电脑 / NAS · SMB", Modifier.focusRequester(initial), selected = kind == SourceKind.SMB, enabled = !busy) { kind = SourceKind.SMB; message = null }
                TvButton("网络目录 · WebDAV", selected = kind == SourceKind.WEBDAV, enabled = !busy) { kind = SourceKind.WEBDAV; message = null }
            }
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FormField("源名称", name, "例如：家里的 NAS", enabled = !busy) { name = it; message = null }
                FormField(if (kind == SourceKind.SMB) "共享地址" else "WebDAV 目录地址", address,
                    if (kind == SourceKind.SMB) "smb://192.168.1.10/Photos" else "https://example.com/dav/Photos/", enabled = !busy) { address = it; message = null }
                Text(if (kind == SourceKind.SMB) "填写共享文件夹名称，可在其后继续填写子目录。" else "填写已开启 WebDAV 的完整目录地址。支持 HTTP / HTTPS 与 Basic 账号认证。", color = TextMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FormField("账号（可选）", username, "无账号则留空", Modifier.weight(1f), enabled = !busy) { username = it; message = null }
                    FormField("密码（可选）", password, "无密码则留空", Modifier.weight(1f), password = true, enabled = !busy) { password = it; message = null }
                }
                if (kind == SourceKind.SMB) FormField("域（可选）", domain, "一般留空；也可在账号中写 域\\账号", enabled = !busy) { domain = it; message = null }
                Text("连接信息仅保存在本机。源设备需要开启共享服务。", color = TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            message?.let { Text(it, color = if (success) Mint else ErrorColor, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp), maxLines = 2) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(if (busy) "正在连接…" else "测试连接", enabled = !busy) {
                    scope.launch {
                        try {
                            val value = source()
                            busy = true; message = "正在读取根目录…"; success = true
                            val result = model.testSource(value)
                            success = result.isSuccess
                            message = result.fold({ "连接成功，根目录找到 $it 个文件夹或媒体" }, ::friendlyError)
                        } catch (error: Exception) { success = false; message = error.message ?: "地址格式不正确" }
                        finally { busy = false }
                    }
                }
                Spacer(Modifier.weight(1f))
                TvButton("取消", enabled = !busy, onClick = onDismiss)
                TvButton("保存", selected = true, enabled = !busy) {
                    scope.launch {
                        try { val value = source(); busy = true; model.saveSource(value); onDismiss() }
                        catch (error: Exception) { success = false; message = error.message ?: "保存失败" }
                        finally { busy = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, placeholder: String, modifier: Modifier = Modifier,
    password: Boolean = false, enabled: Boolean = true, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Column(modifier) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(value = value, onValueChange = { if (it.length <= 2048) onChange(it) }, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = TextMain, fontSize = 15.sp), cursorBrush = SolidColor(Mint),
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .background(Background, RoundedCornerShape(10.dp)).border(if (focused) 2.dp else 1.dp, if (focused) Mint else PanelSoft, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, color = TextMuted.copy(alpha = .7f), fontSize = 15.sp); inner() } })
    }
}
