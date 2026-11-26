// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KestrelTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {}
) {
    val colors = KestrelTheme.colors
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
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
        },
        navigationIcon = navigationIcon,
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.canvas,
            titleContentColor = colors.textPrimary
        )
    )
}

@Composable
fun KestrelCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = KestrelTheme.colors
    val shape = RoundedCornerShape(18.dp)
    val tint = if (highlighted) {
        colors.accentAmber.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.06f)
    }
    val rimColor = if (highlighted) colors.accentAmber else Color.White
    val rimAlpha = if (highlighted) 0.78f else 0.36f

    Box(
        modifier = modifier
            .clip(shape)
            .glassPanel(
                cornerRadius = 18.dp,
                tint = tint,
                rimColor = rimColor,
                rimAlpha = rimAlpha
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun SourceBadge(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = KestrelTheme.colors.accentTeal,
    container: Color = KestrelTheme.colors.accentTealDim
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .border(1.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1
        )
    }
}

@Composable
fun ImdbRatingBadge(
    rating: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF5C518))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                text = "IMDb",
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFF0A0A0A),
                maxLines = 1
            )
        }
        Text(
            text = String.format(Locale.US, "%.1f", rating),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
fun CertificationBadge(
    label: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = KestrelTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!badge.isNullOrBlank()) {
                SourceBadge(
                    label = badge,
                    accent = colors.accentAmber,
                    container = colors.accentAmberDim
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun MediaPosterCard(
    title: String,
    posterUrl: String?,
    sourceTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    posterHeaders: Map<String, String> = emptyMap()
) {
    val colors = KestrelTheme.colors
    val spacing = KestrelTheme.spacing
    val context = LocalContext.current
    val gradientBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color(0xAA090B10)),
            startY = 180f
        )
    }
    val imageRequest = remember(posterUrl, posterHeaders) {
        if (posterUrl.isNullOrBlank()) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(posterUrl)
                .size(280, 420)
                .apply {
                    if (posterHeaders.isNotEmpty()) {
                        val hb = NetworkHeaders.Builder()
                        posterHeaders.forEach { (k, v) -> hb[k] = v }
                        httpHeaders(hb.build())
                    }
                }
                .build()
        }
    }

    Column(
        modifier = modifier
            .width(spacing.posterWidth)
            .clip(RoundedCornerShape(spacing.posterCorner))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(spacing.posterCorner))
                .background(colors.surface)
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(spacing.posterCorner))
        ) {
            if (imageRequest != null) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradientBrush)
            )
            SourceBadge(
                label = sourceTag,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = KestrelTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .glassPanel(
                    cornerRadius = 20.dp,
                    tint = Color.White.copy(alpha = 0.08f),
                    rimAlpha = 0.45f
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accentAmber,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            KestrelPrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

@Composable
fun KestrelPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = KestrelTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accentAmber,
            contentColor = Color(0xFF1A1102),
            disabledContainerColor = colors.surfaceHigh,
            disabledContentColor = colors.textMuted
        )
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun KestrelOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = KestrelTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
