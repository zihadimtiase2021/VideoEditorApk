package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.EditorUiState
import com.example.model.MediaType
import kotlin.math.roundToInt

@Composable
fun AudioPanel(
    uiState: EditorUiState,
    onOriginalVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onPickMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onToggleLoopMusic: (Boolean) -> Unit,
    onSliderFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = uiState.mediaAsset?.type == MediaType.VIDEO
    val audio = uiState.audioConfig
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isVideo) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        tint = primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Audio mixing and background music tracks apply to video exports. Open a video clip to add soundtracks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        // Original Clip Audio
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Original Video Audio",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedButton(
                        onClick = onToggleMute,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (audio.isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("audio_mute_button")
                    ) {
                        Icon(
                            imageVector = if (audio.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = if (audio.isMuted) MaterialTheme.colorScheme.error else primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (audio.isMuted) "Muted" else "Mute",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                StudioSliderRow(
                    label = "Clip Volume",
                    valueText = "${(audio.effectiveOriginalVolume * 100f).roundToInt()}%",
                    value = audio.effectiveOriginalVolume,
                    valueRange = 0f..1f,
                    onValueChange = onOriginalVolumeChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "audio_original_volume_slider"
                )
            }
        }

        // Background Music Track
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(19.dp)
                        )
                        Column {
                            Text(
                                text = "Background Music",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = audio.musicDisplayName ?: "No soundtrack selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (audio.musicUri != null) {
                        IconButton(
                            onClick = onRemoveMusic,
                            modifier = Modifier.testTag("audio_remove_music_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove music track",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = onPickMusic,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (audio.musicUri == null) primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (audio.musicUri == null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("audio_pick_music_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (audio.musicUri == null) {
                            stringResource(R.string.action_add_music)
                        } else {
                            stringResource(R.string.action_replace_music)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                if (audio.musicUri != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    StudioSliderRow(
                        label = "Music Volume",
                        valueText = "${(audio.musicVolume * 100f).roundToInt()}%",
                        value = audio.musicVolume,
                        valueRange = 0f..1f,
                        onValueChange = onMusicVolumeChange,
                        onValueChangeFinished = onSliderFinished,
                        testTag = "audio_music_volume_slider"
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleLoopMusic(!audio.loopMusic) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Loop Music to Match Video",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Switch(
                            checked = audio.loopMusic,
                            onCheckedChange = onToggleLoopMusic,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = primary
                            ),
                            modifier = Modifier.testTag("audio_loop_music_switch")
                        )
                    }
                }
            }
        }
    }
}
