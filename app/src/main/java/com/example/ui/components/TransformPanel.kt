package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.AspectRatioPreset
import com.example.model.TransformConfig
import java.util.Locale

@Composable
fun TransformPanel(
    transform: TransformConfig,
    onSelectAspectRatio: (AspectRatioPreset) -> Unit,
    onRotate90: () -> Unit,
    onToggleFlipH: () -> Unit,
    onToggleFlipV: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onReset: () -> Unit,
    onSliderFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Crop,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Aspect Ratio & Framing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (!transform.isDefault()) {
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.testTag("transform_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(" Reset", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AspectRatioPreset.entries) { preset ->
                FilterChip(
                    selected = transform.aspectRatio == preset,
                    onClick = { onSelectAspectRatio(preset) },
                    label = {
                        Text(
                            text = preset.label,
                            fontWeight = if (transform.aspectRatio == preset) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("aspect_ratio_${preset.name.lowercase()}")
                )
            }
        }

        // Rotate / Flip H / Flip V Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRotate90,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (transform.rotationDegrees != 0) primary else MaterialTheme.colorScheme.outline
                ),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("transform_rotate_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.RotateRight,
                    contentDescription = null,
                    tint = if (transform.rotationDegrees != 0) primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (transform.rotationDegrees == 0) "Rotate" else "${transform.rotationDegrees}°",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = onToggleFlipH,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (transform.flipHorizontal) primary else MaterialTheme.colorScheme.outline
                ),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("transform_flip_h_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Flip,
                    contentDescription = null,
                    tint = if (transform.flipHorizontal) primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Flip H", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = onToggleFlipV,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (transform.flipVertical) primary else MaterialTheme.colorScheme.outline
                ),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("transform_flip_v_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.SwapVert,
                    contentDescription = null,
                    tint = if (transform.flipVertical) primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Flip V", style = MaterialTheme.typography.labelLarge)
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                StudioSliderRow(
                    label = "Crop Zoom",
                    valueText = String.format(Locale.US, "%.2fx", transform.zoomScale),
                    value = transform.zoomScale,
                    valueRange = 1.0f..2.0f,
                    onValueChange = onZoomChange,
                    onValueChangeFinished = onSliderFinished,
                    testTag = "transform_zoom_slider"
                )
            }
        }
    }
}
