// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.core.designsystem

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Backdrop capture: the shell marks its content as a "glass source"; floating glass panels
// (the bottom bar) then draw a blurred copy of whatever sits behind them (API 31+).
// On older devices they fall back to a translucent frosted fill.
// ---------------------------------------------------------------------------------------------

@Stable
class GlassState internal constructor(internal val layer: GraphicsLayer) {
    internal var sourceOrigin by mutableStateOf(Offset.Zero)
}

/**
 * Screens that have their own scrolling content under floating glass controls provide a state here
 * so every glass control below it (chips, circle buttons, header) gets a real blurred backdrop.
 */
val LocalGlassState = compositionLocalOf<GlassState?> { null }

@Composable
fun rememberGlassState(): GlassState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassState(layer) }
}

/** Records the content of this node into [state] while still drawing it normally. */
fun Modifier.glassSource(state: GlassState): Modifier = this
    .onGloballyPositioned { state.sourceOrigin = it.positionInRoot() }
    .drawWithContent {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w > 0 && h > 0) {
            state.layer.record(this, layoutDirection, IntSize(w, h)) {
                this@drawWithContent.drawContent()
            }
            drawLayer(state.layer)
        } else {
            drawContent()
        }
    }

/**
 * Liquid-glass surface: optional blurred backdrop, translucent tint, top sheen and a
 * light-catching gradient rim. Draw-only, so it never changes layout.
 */
@Composable
fun Modifier.glassPanel(
    cornerRadius: Dp,
    state: GlassState? = LocalGlassState.current,
    blurRadius: Dp = 22.dp,
    tint: Color = Color.White.copy(alpha = 0.08f),
    rimColor: Color = Color.White,
    rimAlpha: Float = 0.5f,
    sheen: Boolean = true,
    depth: Boolean = true
): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blurLayer = rememberGraphicsLayer()
    val canBlur = state != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val positioned = if (canBlur) {
        Modifier.onGloballyPositioned { origin = it.positionInRoot() }
    } else {
        Modifier
    }
    return this
        .then(positioned)
        .drawBehind {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@drawBehind
            val r = min(cornerRadius.toPx(), min(w, h) / 2f)
            val radius = CornerRadius(r, r)

            if (canBlur && state != null) {
                val shift = state.sourceOrigin - origin
                val blurPx = blurRadius.toPx()
                blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                blurLayer.record(this, layoutDirection, IntSize(w.toInt(), h.toInt())) {
                    translate(shift.x, shift.y) {
                        drawLayer(state.layer)
                    }
                }
                val clip = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, h, radius))
                }
                clipPath(clip) {
                    drawLayer(blurLayer)
                }
            }

            drawRoundRect(color = tint, cornerRadius = radius)

            if (depth) {
                // Thickness: glass gets slightly denser toward the bottom edge.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f)),
                        startY = h * 0.45f,
                        endY = h
                    ),
                    cornerRadius = radius
                )
            }

            if (sheen) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                        startY = 0f,
                        endY = h * 0.6f
                    ),
                    cornerRadius = radius
                )
                // Specular glint in the top-left corner, like light hitting curved glass.
                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(w * 0.18f, 0f),
                        radius = max(w, h) * 0.55f
                    ),
                    cornerRadius = radius
                )
            }

            if (depth) {
                // Faint inner rim = refraction edge of the glass.
                val inner = 3.dp.toPx()
                if (w > inner * 2 && h > inner * 2) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.05f),
                        topLeft = Offset(inner, inner),
                        size = Size(w - inner * 2, h - inner * 2),
                        cornerRadius = CornerRadius(max(r - inner, 0f)),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }

            val stroke = 1.2.dp.toPx()
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        rimColor.copy(alpha = rimAlpha),
                        rimColor.copy(alpha = 0.07f),
                        rimColor.copy(alpha = 0.07f),
                        rimColor.copy(alpha = rimAlpha * 0.65f)
                    ),
                    start = Offset.Zero,
                    end = Offset(w, h)
                ),
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(w - stroke, h - stroke),
                cornerRadius = CornerRadius(max(r - stroke / 2f, 0f)),
                style = Stroke(width = stroke)
            )
        }
}

// ---------------------------------------------------------------------------------------------
// Backgrounds
// ---------------------------------------------------------------------------------------------

enum class KestrelBackdrop { Dark, Purple }

@Composable
fun KestrelBackground(
    modifier: Modifier = Modifier,
    style: KestrelBackdrop = KestrelBackdrop.Dark,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                when (style) {
                    KestrelBackdrop.Dark -> {
                        drawRect(color = Color(0xFF090B10))
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0x2E4F5BD5), Color.Transparent),
                                center = Offset(w * 0.15f, 0f),
                                radius = w * 1.1f
                            )
                        )
                    }

                    KestrelBackdrop.Purple -> {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF46367F),
                                    Color(0xFF31275E),
                                    Color(0xFF1D1738)
                                )
                            )
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0x558F73FF), Color.Transparent),
                                center = Offset(w * 0.1f, h * 0.05f),
                                radius = w * 1.0f
                            )
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color(0x33FF7AC8), Color.Transparent),
                                center = Offset(w * 0.95f, h * 0.7f),
                                radius = w * 0.9f
                            )
                        )
                    }
                }
            },
        content = content
    )
}

// ---------------------------------------------------------------------------------------------
// Layout helpers
// ---------------------------------------------------------------------------------------------

private val NavBarHeight = 68.dp
private val NavBarOuterBottom = 12.dp

/** Space a scrolling screen must leave at the bottom so content clears the floating nav bar. */
@Composable
fun kestrelNavClearance(): Dp =
    NavBarHeight + NavBarOuterBottom + 20.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

// ---------------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------------

@Composable
fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    tint: Color = KestrelTheme.colors.textPrimary,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .glassPanel(cornerRadius = diameter / 2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(diameter * 0.5f)
        )
    }
}

@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KestrelTheme.colors
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .glassPanel(
                cornerRadius = 40.dp,
                tint = if (selected) colors.accentAmber.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f),
                rimColor = if (selected) colors.accentAmber else Color.White,
                rimAlpha = if (selected) 0.8f else 0.4f
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) colors.accentAmber else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null
) {
    val colors = KestrelTheme.colors
    val shape = RoundedCornerShape(32.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .glassPanel(cornerRadius = 32.dp, tint = Color.White.copy(alpha = 0.08f), rimAlpha = 0.5f)
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = colors.textPrimary),
            placeholder = {
                Text(
                    text = placeholder,
                    color = colors.textMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.textSecondary
                )
            },
            trailingIcon = {
                if (value.isNotEmpty() && onClear != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClear),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Clear",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(
                onSearch = { onSearch?.invoke() },
                onDone = { onSearch?.invoke() }
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accentAmber
            )
        )
    }
}

@Composable
fun GlassScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = KestrelTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null) {
            GlassCircleButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = if (onBack == null) 32.sp else 22.sp,
                    lineHeight = if (onBack == null) 38.sp else 28.sp,
                    fontWeight = if (onBack == null) FontWeight.Medium else FontWeight.SemiBold,
                    letterSpacing = (-0.4).sp
                ),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sliding glass tab selector (used by Library)
// ---------------------------------------------------------------------------------------------

@Composable
fun GlassTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (labels.isEmpty()) return
    val colors = KestrelTheme.colors
    val density = LocalDensity.current
    val tabsHeight = 46.dp
    val inset = 4.dp
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "glassTabsPosition"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(tabsHeight)
            .glassPanel(cornerRadius = tabsHeight / 2, tint = Color.White.copy(alpha = 0.05f), rimAlpha = 0.4f)
    ) {
        val tabWidth = (maxWidth - inset * 2) / labels.size
        val tabWidthPx = with(density) { tabWidth.toPx() }
        val insetPx = with(density) { inset.toPx() }

        Box(
            modifier = Modifier
                .offset { IntOffset((insetPx + position * tabWidthPx).roundToInt(), insetPx.roundToInt()) }
                .size(width = tabWidth, height = tabsHeight - inset * 2)
                .glassPanel(
                    cornerRadius = (tabsHeight - inset * 2) / 2,
                    tint = colors.accentAmber.copy(alpha = 0.2f),
                    rimColor = colors.accentAmber,
                    rimAlpha = 0.7f
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = inset)
        ) {
            labels.forEachIndexed { index, label ->
                val interaction = remember { MutableInteractionSource() }
                val closeness = 1f - min(abs(position - index), 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = interaction, indication = null) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = lerp(colors.textSecondary, colors.accentAmber, closeness),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Floating liquid-glass bottom navigation with a sliding, stretching, draggable highlight
// ---------------------------------------------------------------------------------------------

@Immutable
data class GlassNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)

@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: GlassState? = null
) {
    val count = items.size
    if (count == 0) return
    val density = LocalDensity.current
    val inset = 6.dp
    val maxIndex = (count - 1).toFloat()

    val crimsonAccent = Color(0xFFE53935)
    val inactiveTint = Color(0xFFA0A0AA)
    val barTint = Color(0xEC141113)
    val indicatorFill = Color(0xFF3A1619)

    var dragPos by remember { mutableStateOf<Float?>(null) }
    val target = dragPos ?: selectedIndex.toFloat()
    val position by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 360f),
        label = "glassNavPosition"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(NavBarHeight)
            .glassPanel(
                cornerRadius = NavBarHeight / 2,
                state = state,
                blurRadius = 24.dp,
                tint = barTint,
                rimColor = Color.White,
                rimAlpha = 0.22f
            )
    ) {
        val itemWidth = (maxWidth - inset * 2) / count
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val insetPx = with(density) { inset.toPx() }
        val indicatorHeight = NavBarHeight - inset * 2

        // Sliding crimson capsule highlight with stretch + lens pop while traveling between tabs.
        Box(
            modifier = Modifier
                .offset { IntOffset((insetPx + position * itemWidthPx).roundToInt(), insetPx.roundToInt()) }
                .size(width = itemWidth, height = indicatorHeight)
                .graphicsLayer {
                    val stretch = min(abs(position - target), 1f)
                    val lens = if (dragPos != null) 1.08f else 1f
                    scaleX = (1f + 0.22f * stretch) * lens
                    scaleY = (1f - 0.06f * stretch) * lens
                }
                .glassPanel(
                    cornerRadius = indicatorHeight / 2,
                    tint = indicatorFill.copy(alpha = if (dragPos != null) 0.94f else 0.88f),
                    rimColor = crimsonAccent,
                    rimAlpha = 0.45f,
                    state = null
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = inset)
                .pointerInput(count, itemWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragPos = (offset.x / itemWidthPx - 0.5f).coerceIn(0f, maxIndex)
                        },
                        onDragEnd = {
                            val finalPos = dragPos
                            dragPos = null
                            if (finalPos != null) {
                                onSelect(finalPos.roundToInt().coerceIn(0, count - 1))
                            }
                        },
                        onDragCancel = { dragPos = null },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val current = dragPos ?: selectedIndex.toFloat()
                            dragPos = (current + dragAmount / itemWidthPx).coerceIn(0f, maxIndex)
                        }
                    )
                }
        ) {
            items.forEachIndexed { index, item ->
                val interaction = remember { MutableInteractionSource() }
                val closeness = (1f - min(abs(position - index), 1f)).coerceIn(0f, 1f)
                val tintColor = lerp(inactiveTint, crimsonAccent, closeness)
                val iconScale by animateFloatAsState(
                    targetValue = if (index == selectedIndex && dragPos == null) 1.10f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.58f, stiffness = 420f),
                    label = "tabIconScale"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = interaction, indication = null) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = if (closeness > 0.5f) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            tint = tintColor,
                            modifier = Modifier
                                .size(23.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                        )
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            fontWeight = if (closeness > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                            color = tintColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
