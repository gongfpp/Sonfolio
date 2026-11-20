package com.gongfpp.sonfolio.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gongfpp.sonfolio.AmberPale
import com.gongfpp.sonfolio.CardSurface
import com.gongfpp.sonfolio.Green
import com.gongfpp.sonfolio.Ink
import com.gongfpp.sonfolio.InkSoft
import com.gongfpp.sonfolio.Line
import com.gongfpp.sonfolio.PaleGreen
import com.gongfpp.sonfolio.Paper

internal val OnboardingError = Color(0xFFB23B2E)
internal val AmberInk = Color(0xFF694E00)

/**
 * 引导页统一骨架：内容区可滚动，主操作吸底。
 * 不设固定高度，字体放大 2.0 倍时按钮仍可达。
 */
@Composable
internal fun OnboardingFrame(
    step: OnboardingStep,
    onBack: (() -> Unit)?,
    content: @Composable () -> Unit,
    bottom: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Paper).statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        if (step.title != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "返回", tint = Ink)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(step.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(20.dp))
        }
        bottom()
    }
}

/** 品牌行：三根声波条组成的标记与产品名，与主界面保持同一识别符号。 */
@Composable
internal fun BrandRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Green), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.5.dp)) {
                listOf(6.dp, 13.dp, 4.dp).forEach { barHeight ->
                    Box(Modifier.width(2.4.dp).height(barHeight).clip(RoundedCornerShape(2.dp)).background(PaleGreen))
                }
            }
        }
        Spacer(Modifier.width(9.dp))
        Text("声迹", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
internal fun Headline(text: String, size: Int = 27) {
    Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = (size + 11).sp)
}

@Composable
internal fun Body(text: String) {
    Text(text, fontSize = 13.sp, color = InkSoft, lineHeight = 21.sp)
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = InkSoft, lineHeight = 18.sp)
}

/** 单选圈：18dp，与视觉稿一致，不使用 Material RadioButton 的 48dp 热区。 */
@Composable
internal fun SelectDot(selected: Boolean) {
    // 主题色是 @Composable 取值，必须先在这里取出，才能进入非组合的绘制作用域。
    val active = Green
    val idle = Line
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        drawCircle(color = if (selected) active else idle, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        if (selected) drawCircle(color = active, radius = size.minDimension * 0.21f)
    }
}

/**
 * 可选卡片。选中态为淡绿底 + 1.5dp 绿边，不做浮起与渐变；整卡可点。
 */
@Composable
internal fun ChoiceCard(
    selected: Boolean,
    title: String,
    description: String,
    meta: String,
    onClick: () -> Unit,
    badge: String? = null,
    enabled: Boolean = true,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) PaleGreen else CardSurface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Green else Line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) Green else Ink,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (selected) Green else PaleGreen).padding(horizontal = 5.dp, vertical = 2.dp)) {
                        Text(
                            badge,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) CardSurface else Green,
                            lineHeight = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            SelectDot(selected)
        }
        Spacer(Modifier.height(7.dp))
        Text(description, fontSize = 12.sp, color = InkSoft, lineHeight = 19.sp)
        Spacer(Modifier.height(6.dp))
        Text(meta, fontSize = 11.sp, color = InkSoft, lineHeight = 17.sp)
    }
}

/** 琥珀块只承载“费用 / 上传 / 边界”这类必须被看见的事实说明。 */
@Composable
internal fun AmberNote(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AmberPale).padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("!", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AmberInk)
        Text(text, fontSize = 11.sp, color = AmberInk, lineHeight = 17.sp)
    }
}

/** 三段进度：只表达“当前是第几步”，不可点击跳转。 */
@Composable
internal fun StageBars(active: Int, total: Int = 3) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (index < active) Green else Line),
            )
        }
    }
}

@Composable
internal fun PrimaryAction(
    label: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        enabled = enabled && !loading,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = if (enabled) ButtonDefaults.buttonColors(containerColor = Green, contentColor = CardSurface)
        else ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = InkSoft,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CardSurface)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SecondaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = InkSoft)
    }
}

@Composable
internal fun Hint(text: String, centered: Boolean = false) {
    Text(
        text,
        fontSize = 11.sp,
        color = InkSoft,
        lineHeight = 17.sp,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun InlineMessage(text: String?, error: Boolean = false) {
    if (text.isNullOrBlank()) return
    Spacer(Modifier.height(8.dp))
    Text(text, fontSize = 12.sp, color = if (error) OnboardingError else Green, lineHeight = 18.sp)
}

/** 同意项。音频授权与文字总结授权是两件事，互不替代，因此各自独立勾选。 */
@Composable
internal fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked, onCheckedChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.sp, color = InkSoft, lineHeight = 18.sp)
    }
}
