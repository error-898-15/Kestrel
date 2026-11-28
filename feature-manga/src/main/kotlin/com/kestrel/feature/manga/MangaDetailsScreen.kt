// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.feature.manga

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kestrel.core.designsystem.GlassCircleButton
import com.kestrel.core.designsystem.KestrelBackdrop
import com.kestrel.core.designsystem.KestrelBackground
import com.kestrel.core.designsystem.KestrelCard
import com.kestrel.core.designsystem.KestrelPrimaryButton
import com.kestrel.core.designsystem.KestrelTheme
import com.kestrel.core.designsystem.rememberKestrelImageRequest

@Composable
fun MangaDetailsScreen(
    onBack: () -> Unit,
    onReadChapter: (extensionId: String, sourceId: String, mangaUrl: String, chapterUrl: String) -> Unit,
    viewModel: MangaDetailsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = KestrelTheme.colors

    KestrelBackground(modifier = Modifier.fillMaxSize(), style = KestrelBackdrop.Dark) {
        val details = state.details
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentAmber)
                }
            }
            details == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "Failed to load manga details", color = colors.textSecondary)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassCircleButton(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back",
                                onClick = onBack
                            )
                            GlassCircleButton(
                                icon = if (details.isFavorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = "Bookmark",
                                onClick = viewModel::toggleFavorite,
                                tint = if (details.isFavorite) colors.accentAmber else colors.textPrimary
                            )
                        }
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            AsyncImage(
                                model = rememberKestrelImageRequest(details.summary.coverUrl, emptyMap(), 360, 516),
                                contentDescription = details.summary.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(172.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = details.summary.title,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = colors.textPrimary
                                )
                                details.summary.author?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                                }
                                Text(
                                    text = details.summary.sourceName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.accentTeal
                                )
                                Spacer(Modifier.height(6.dp))
                                details.chapters.firstOrNull()?.let { first ->
                                    KestrelPrimaryButton(
                                        text = "Start Reading",
                                        onClick = {
                                            onReadChapter(
                                                viewModel.extensionId,
                                                viewModel.sourceId,
                                                viewModel.mangaUrl,
                                                first.url
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    details.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        item {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }

                    item {
                        Text(
                            text = "Chapters (${details.chapters.size})",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }

                    items(details.chapters.distinctBy { it.id }, key = { it.id }) { chapter ->
                        KestrelCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onReadChapter(
                                    viewModel.extensionId,
                                    viewModel.sourceId,
                                    viewModel.mangaUrl,
                                    chapter.url
                                )
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chapter.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                chapter.scanlator?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
