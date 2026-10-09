@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.localtv.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

val Background = Color(0xFF10151E)
val Panel = Color(0xFF1B2330)
val PanelSoft = Color(0xFF222D3D)
val Mint = Color(0xFF77E8C5)
val TextMain = Color(0xFFF3F6FA)
val TextMuted = Color(0xFF9BAAC0)
val ErrorColor = Color(0xFFFFB7B0)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Background,
        background = Background, onBackground = TextMain, surface = Panel, onSurface = TextMain)) {
        CompositionLocalProvider(LocalContentColor provides TextMain, content = content)
    }
}

@Composable
fun TvButton(label: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 42.dp),
        colors = ButtonDefaults.colors(containerColor = if (selected) PanelSoft else Panel,
            contentColor = if (selected) Mint else TextMain, focusedContainerColor = Mint, focusedContentColor = Background),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        Text(label, fontSize = 14.sp)
    }
}

enum class Glyph { SCREEN, SERVER, CLOUD, FOLDER, PHOTO, VIDEO, PLUS, ARROW, LOGO }

@Composable
fun MediaGlyph(glyph: Glyph, modifier: Modifier = Modifier.size(36.dp), color: Color = Mint) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val stroke = Stroke((w * .055f).coerceAtLeast(1f))
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), stroke.width)
        when (glyph) {
            Glyph.SCREEN, Glyph.LOGO -> {
                drawRoundRect(color, Offset(w * .08f, h * .14f), Size(w * .84f, h * .6f), androidx.compose.ui.geometry.CornerRadius(w * .08f), style = stroke)
                line(.32f, .87f, .68f, .87f); line(.5f, .75f, .5f, .87f)
                if (glyph == Glyph.LOGO) drawPath(Path().apply { moveTo(.42f * w, .28f * h); lineTo(.66f * w, .44f * h); lineTo(.42f * w, .6f * h); close() }, color)
            }
            Glyph.SERVER -> {
                repeat(3) { i ->
                    drawRoundRect(color, Offset(w * .13f, h * (.12f + i * .27f)), Size(w * .74f, h * .2f), androidx.compose.ui.geometry.CornerRadius(w * .045f), style = stroke)
                    drawCircle(color, w * .035f, Offset(w * .74f, h * (.22f + i * .27f)))
                }
            }
            Glyph.CLOUD -> {
                drawPath(Path().apply {
                    moveTo(w * .22f, h * .76f); cubicTo(-w * .1f, h * .68f, w * .02f, h * .34f, w * .28f, h * .4f)
                    cubicTo(w * .28f, h * .06f, w * .74f, h * .06f, w * .78f, h * .43f)
                    cubicTo(w * 1.06f, h * .4f, w * 1.05f, h * .76f, w * .8f, h * .76f); close()
                }, color, style = stroke)
            }
            Glyph.FOLDER -> drawPath(Path().apply {
                moveTo(w * .08f, h * .24f); lineTo(w * .4f, h * .24f); lineTo(w * .5f, h * .36f)
                lineTo(w * .92f, h * .36f); lineTo(w * .92f, h * .8f); lineTo(w * .08f, h * .8f); close()
            }, color, style = stroke)
            Glyph.PHOTO -> {
                drawRoundRect(color, Offset(w * .08f, h * .14f), Size(w * .84f, h * .72f), androidx.compose.ui.geometry.CornerRadius(w * .055f), style = stroke)
                drawCircle(color, w * .08f, Offset(w * .7f, h * .34f))
                line(.12f, .75f, .37f, .45f); line(.37f, .45f, .61f, .73f); line(.61f, .73f, .74f, .59f); line(.74f, .59f, .88f, .76f)
            }
            Glyph.VIDEO -> {
                drawCircle(color.copy(alpha = .15f), w * .47f)
                drawPath(Path().apply { moveTo(w * .39f, h * .27f); lineTo(w * .75f, h * .5f); lineTo(w * .39f, h * .73f); close() }, color)
            }
            Glyph.PLUS -> { line(.5f, .17f, .5f, .83f); line(.17f, .5f, .83f, .5f) }
            Glyph.ARROW -> { line(.2f, .5f, .8f, .5f); line(.55f, .25f, .8f, .5f); line(.55f, .75f, .8f, .5f) }
        }
    }
}
