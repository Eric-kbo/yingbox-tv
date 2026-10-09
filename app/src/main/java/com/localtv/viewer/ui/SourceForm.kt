package com.localtv.viewer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.localtv.viewer.pairing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
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
    var phone by remember { mutableStateOf(existing == null) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf<SourcePairingServer?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember(phone) { FocusRequester() }
    val nameScroll = remember { BringIntoViewRequester() }
    LaunchedEffect(phone) { delay(120); runCatching { initial.requestFocus() } }
    LaunchedEffect(advanced) { if (advanced) { delay(100); nameScroll.bringIntoView() } }

    suspend fun connect(draft: SourceDraft): PairingResponse {
        if (busy) return PairingResponse(false, "正在连接，请稍等")
        busy = true; success = true; message = "正在连接并读取目录…"
        return try {
            val value = draft.normalized(existing?.id)
            model.testSource(value).getOrThrow()
            model.saveSource(value)
            success = true; message = "已添加「${value.name}」"
            PairingResponse(true, "已保存到电视。可以关闭此页面，回到电视打开「${value.name}」。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            success = false; message = friendlyError(error)
            PairingResponse(false, message ?: "连接失败")
        } finally { busy = false }
    }
    fun submit(draft: SourceDraft): PairingReply {
        val response = PairingReply()
        val job = scope.launch {
            val result = connect(draft)
            response.complete(result)
            if (result.success) { delay(1200); onDismiss() }
        }
        job.invokeOnCompletion { if (!response.isDone) response.complete(PairingResponse(false, "电视添加页面已关闭")) }
        return response
    }
    LaunchedEffect(phone) {
        if (!phone) return@LaunchedEffect
        var pendingServer: SourcePairingServer? = null
        try {
            val created = withContext(Dispatchers.IO) {
                val ip = pairingAddress(context) ?: throw IllegalStateException("电视没有可用的局域网地址，请先连接 Wi-Fi 或网线。")
                SourcePairingServer(ip, context.assets.open("pairing.html").bufferedReader().use { it.readText() }, onSubmit = ::submit).also { pendingServer = it }
            }
            server = created
            qr = withContext(Dispatchers.Default) {
                val matrix = QRCodeWriter().encode(created.url, BarcodeFormat.QR_CODE, 512, 512)
                Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                    setPixels(IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 512, 0, 0, 512, 512)
                }
            }
            kotlinx.coroutines.awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { pairingError = error.message ?: "暂时无法扫码连接，请用遥控器填写" }
        finally { pendingServer?.close(); server = null; qr = null }
    }
    DisposableEffect(Unit) { onDispose { server?.close() } }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(700.dp).fillMaxHeight(.91f).background(Panel, RoundedCornerShape(16.dp)).padding(24.dp)) {
            Text(if (existing == null) "添加媒体源" else "修改媒体源", fontSize = 25.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("手机扫码", if (phone) Modifier.focusRequester(initial) else Modifier, selected = phone, enabled = !busy) { phone = true }
                TvButton("遥控器填写", if (!phone) Modifier.focusRequester(initial) else Modifier, selected = !phone, enabled = !busy) { phone = false }
            }
            Spacer(Modifier.height(16.dp))
            if (phone) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    Box(Modifier.size(216.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp), contentAlignment = Alignment.Center) {
                        qr?.let { Image(it.asImageBitmap(), contentDescription = "用手机扫描添加源", modifier = Modifier.fillMaxSize()) }
                            ?: Text("生成二维码…", color = Background, fontSize = 14.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("用手机填写，电视自动保存", fontSize = 21.sp)
                        Text("1  手机与电视连接同一网络\n2  扫码后填写共享地址和账号\n3  点击连接，验证后即可使用", fontSize = 14.sp, color = TextMuted, lineHeight = 27.sp)
                        Text("支持电脑 / NAS 的 SMB 共享，以及 WebDAV 目录。电脑需先开启文件共享。", fontSize = 12.sp, color = TextMuted, lineHeight = 20.sp)
                        server?.let { Text("也可在浏览器输入：\n${it.url}", fontSize = 10.sp, color = TextMuted, maxLines = 3) }
                        pairingError?.let { Text(it, color = ErrorColor, fontSize = 12.sp) }
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvButton("电脑 / NAS · SMB", selected = kind == SourceKind.SMB, enabled = !busy) { kind = SourceKind.SMB; message = null }
                        TvButton("网络目录 · WebDAV", selected = kind == SourceKind.WEBDAV, enabled = !busy) { kind = SourceKind.WEBDAV; message = null }
                    }
                    FormField(if (kind == SourceKind.SMB) "共享地址" else "WebDAV 目录地址", address,
                        if (kind == SourceKind.SMB) "192.168.1.10/Photos" else "https://example.com/dav/Photos/", enabled = !busy) { address = it; message = null }
                    Text(if (kind == SourceKind.SMB) "设备 IP / 共享名；也可填写 smb:// 开头的地址。" else "完整的 WebDAV 目录地址，普通网盘分享链接不能使用。", color = TextMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        FormField("账号（可选）", username, "无需登录则留空", Modifier.weight(1f), enabled = !busy) { username = it; message = null }
                        FormField("密码（可选）", password, "无需登录则留空", Modifier.weight(1f), password = true, enabled = !busy) { password = it; message = null }
                    }
                    TvButton(if (advanced) "收起名称与高级设置" else "名称与高级设置", enabled = !busy) { advanced = !advanced }
                    if (advanced) {
                        FormField("显示名称（可选）", name, "默认使用文件夹名称", Modifier.bringIntoViewRequester(nameScroll), enabled = !busy) { name = it }
                        if (kind == SourceKind.SMB) FormField("域（可选）", domain, "一般留空", enabled = !busy) { domain = it }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            message?.let { Text(it, color = if (success) Mint else ErrorColor, fontSize = 13.sp, maxLines = 2); Spacer(Modifier.height(10.dp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (phone) "二维码 10 分钟内有效" else "返回收起键盘 · ↑↓ 切换输入项", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f)); TvButton("取消", enabled = !busy, onClick = onDismiss)
                if (!phone) TvButton(if (busy) "正在连接…" else "连接并保存", selected = true, enabled = !busy) {
                    scope.launch { if (connect(SourceDraft(kind, address, username, password, name, domain)).success) onDismiss() }
                }
            }
        }
    }
}

@Composable
fun FormField(label: String, value: String, placeholder: String, modifier: Modifier = Modifier,
    password: Boolean = false, enabled: Boolean = true, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(value = value, onValueChange = { if (it.length <= 2048) onChange(it) }, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = TextMain, fontSize = 15.sp), cursorBrush = SolidColor(Mint),
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.onPreviewKeyEvent { event ->
                when {
                    event.key == Key.DirectionDown || event.key == Key.DirectionUp -> {
                        if (event.type == KeyEventType.KeyDown) { keyboard?.hide(); focusManager.moveFocus(if (event.key == Key.DirectionDown) FocusDirection.Next else FocusDirection.Previous) }; true
                    }
                    else -> false
                }
            }.onFocusChanged { focused = it.isFocused }
                .background(Background, RoundedCornerShape(10.dp)).border(if (focused) 2.dp else 1.dp, if (focused) Mint else PanelSoft, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, color = TextMuted.copy(alpha = .7f), fontSize = 15.sp); inner() } })
    }
}
