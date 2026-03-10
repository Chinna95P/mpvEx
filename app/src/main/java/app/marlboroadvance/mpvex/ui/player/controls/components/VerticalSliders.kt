package app.marlboroadvance.mpvex.ui.player.controls.components

import app.marlboroadvance.mpvex.ui.icons.AppIcon
import app.marlboroadvance.mpvex.ui.icons.Icon as AppIconComposable
import app.marlboroadvance.mpvex.ui.icons.Icons
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.ui.theme.spacing
import kotlin.math.roundToInt

fun percentage(
  value: Float,
  range: ClosedFloatingPointRange<Float>,
): Float {
  val span = range.endInclusive - range.start
  if (span <= 0f) return 0f
  return ((value - range.start) / span).coerceIn(0f, 1f)
}

fun percentage(
  value: Int,
  range: ClosedRange<Int>,
): Float {
  val span = (range.endInclusive - range.start).coerceAtLeast(1)
  return ((value - range.start).toFloat() / span.toFloat()).coerceIn(0f, 1f)
}

private fun effectiveVolumePercentage(
  volume: Int,
  range: ClosedRange<Int>,
  mpvVolume: Int,
): Int {
  if (volume <= 0) return 0
  val baseProgress = percentage(volume, range)
  val mpvProgress = mpvVolume.coerceIn(0, 100) / 100f
  return (baseProgress * mpvProgress * 100f).roundToInt().coerceIn(0, 100)
}

private fun progressFromDrag(offset: Offset, trackHeightPx: Int): Float {
  if (trackHeightPx <= 0) return 0f
  return (1f - (offset.y / trackHeightPx.toFloat())).coerceIn(0f, 1f)
}

@Composable
private fun ElasticSliderTrack(
  progress: Float,
  accentColors: List<Color>,
  modifier: Modifier = Modifier,
  overflowProgress: Float = 0f,
  overflowColors: List<Color> = listOf(
    MaterialTheme.colorScheme.error,
    MaterialTheme.colorScheme.errorContainer,
  ),
  onProgressChange: ((Float) -> Unit)? = null,
  onDraggingChange: (Boolean) -> Unit = {},
) {
  var isDragging by remember { mutableStateOf(false) }
  var trackHeightPx by remember { mutableIntStateOf(0) }

  val animatedProgress by animateFloatAsState(
    targetValue = progress.coerceIn(0f, 1f),
    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow),
    label = "elastic_track_progress",
  )
  val animatedOverflow by animateFloatAsState(
    targetValue = overflowProgress.coerceIn(0f, 1f),
    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
    label = "elastic_track_overflow",
  )
  val trackWidth by animateDpAsState(
    targetValue = if (isDragging) 52.dp else 44.dp,
    animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
    label = "elastic_track_width",
  )
  val knobScaleX by animateFloatAsState(
    targetValue = if (isDragging) 1.18f else 1f,
    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
    label = "elastic_knob_scale_x",
  )
  val knobScaleY by animateFloatAsState(
    targetValue = if (isDragging) 0.88f else 1f,
    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
    label = "elastic_knob_scale_y",
  )
  val glowAlpha by animateFloatAsState(
    targetValue = if (isDragging) 0.35f else 0.18f,
    animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
    label = "elastic_track_glow",
  )
  val knobSize = 22.dp
  val knobSizePx = with(LocalDensity.current) { knobSize.roundToPx() }
  val knobTravelPx = (trackHeightPx - knobSizePx).coerceAtLeast(0)

  Box(
    modifier =
      modifier
        .height(176.dp)
        .width(trackWidth)
        .onSizeChanged { trackHeightPx = it.height }
        .clip(RoundedCornerShape(28.dp))
        .background(
          Brush.verticalGradient(
            listOf(
              MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
              MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f),
              MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.95f),
            ),
          ),
        )
        .border(
          BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
          ),
          RoundedCornerShape(28.dp),
        )
        .pointerInput(onProgressChange, trackHeightPx) {
          if (onProgressChange == null) return@pointerInput
          detectDragGestures(
            onDragStart = { offset ->
              isDragging = true
              onDraggingChange(true)
              onProgressChange(progressFromDrag(offset, trackHeightPx))
            },
            onDragEnd = {
              isDragging = false
              onDraggingChange(false)
            },
            onDragCancel = {
              isDragging = false
              onDraggingChange(false)
            },
            onDrag = { change, _ ->
              change.consume()
              onProgressChange(progressFromDrag(change.position, trackHeightPx))
            },
          )
        },
    contentAlignment = Alignment.BottomCenter,
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .fillMaxHeight()
          .background(
            Brush.verticalGradient(
              listOf(
                accentColors.last().copy(alpha = glowAlpha * 0.18f),
                accentColors.first().copy(alpha = glowAlpha),
              ),
            ),
          ),
    )

    Column(
      modifier =
        Modifier
          .fillMaxHeight()
          .padding(vertical = 16.dp),
      verticalArrangement = Arrangement.SpaceBetween,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      repeat(6) {
        Box(
          modifier =
            Modifier
              .width(12.dp)
              .height(2.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)),
        )
      }
    }

    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .fillMaxHeight(animatedProgress)
          .clip(RoundedCornerShape(28.dp))
          .background(Brush.verticalGradient(accentColors))
          .align(Alignment.BottomCenter),
    )

    if (animatedOverflow > 0f) {
      Box(
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .fillMaxHeight(animatedOverflow * 0.35f)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(overflowColors)),
      )
    }

    Box(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .offset {
            IntOffset(
              x = 0,
              y = -(animatedProgress * knobTravelPx).roundToInt(),
            )
          }.size(knobSize)
          .graphicsLayer(
            scaleX = knobScaleX,
            scaleY = knobScaleY,
          )
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surface)
          .border(
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
            CircleShape,
          ),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        modifier =
          Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(accentColors.first()),
      )
    }
  }
}

@Composable
private fun AdjustmentCard(
  valueText: String,
  label: String,
  icon: AppIcon,
  progress: Float,
  modifier: Modifier = Modifier,
  overflowProgress: Float = 0f,
  accentColors: List<Color>,
  onProgressChange: ((Float) -> Unit)? = null,
) {
  var isInteracting by remember { mutableStateOf(false) }
  val cardScale by animateFloatAsState(
    targetValue = if (isInteracting) 1.03f else 1f,
    animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
    label = "adjustment_card_scale",
  )
  val cardAlpha by animateFloatAsState(
    targetValue = if (isInteracting) 0.98f else 1f,
    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
    label = "adjustment_card_alpha",
  )

  Surface(
    modifier =
      modifier
        .graphicsLayer(
          scaleX = cardScale,
          scaleY = cardScale,
          alpha = cardAlpha,
        )
        .widthIn(min = 94.dp),
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    tonalElevation = 2.dp,
    shadowElevation = if (isInteracting) 14.dp else 6.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            shape = CircleShape,
            color = accentColors.first().copy(alpha = 0.16f),
          ) {
            AppIconComposable(
              imageVector = icon,
              contentDescription = null,
              modifier = Modifier.padding(8.dp).size(18.dp),
              tint = accentColors.first(),
            )
          }

          Column(horizontalAlignment = Alignment.Start) {
            Text(
              text = valueText,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.ExtraBold,
              textAlign = TextAlign.Start,
            )
            Text(
              text = label,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      ElasticSliderTrack(
        progress = progress,
        overflowProgress = overflowProgress,
        accentColors = accentColors,
        onProgressChange = onProgressChange?.let { callback ->
          { newProgress ->
            isInteracting = true
            callback(newProgress)
          }
        },
        onDraggingChange = { isInteracting = it },
      )
    }
  }
}

@Composable
fun BrightnessSlider(
  brightness: Float,
  range: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
  onValueChange: ((Float) -> Unit)? = null,
) {
  val coercedBrightness = brightness.coerceIn(range)
  val brightnessProgress = percentage(coercedBrightness, range)
  val brightnessIcon =
    when (brightnessProgress) {
      in 0f..0.3f -> Icons.Default.BrightnessLow
      in 0.3f..0.6f -> Icons.Default.BrightnessMedium
      else -> Icons.Default.BrightnessHigh
    }

  AdjustmentCard(
    valueText = "${(coercedBrightness * 100f).roundToInt()}%",
    label = stringResource(R.string.player_sheets_filters_brightness),
    icon = brightnessIcon,
    progress = brightnessProgress,
    modifier = modifier,
    accentColors =
      listOf(
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondaryContainer,
      ),
    onProgressChange = onValueChange?.let { callback ->
      { callback(range.start + ((range.endInclusive - range.start) * it)) }
    },
  )
}

@Composable
fun VolumeSlider(
  volume: Int,
  mpvVolume: Int,
  range: ClosedRange<Int>,
  boostRange: ClosedRange<Int>?,
  modifier: Modifier = Modifier,
  displayAsPercentage: Boolean = false,
  onValueChange: ((Int) -> Unit)? = null,
) {
  val effectivePercentage = effectiveVolumePercentage(volume, range, mpvVolume)
  val boostVolume = (mpvVolume - 100).coerceAtLeast(0)
  val effectiveDisplayPercentage = effectivePercentage + boostVolume
  val displayText =
    if (displayAsPercentage) {
      "$effectiveDisplayPercentage%"
    } else {
      stringResource(R.string.volume_slider_absolute_value, volume + boostVolume)
    }

  val volumeIcon =
    when (effectiveDisplayPercentage) {
      0 -> Icons.Default.VolumeOff
      in 1..30 -> Icons.Default.VolumeMute
      in 31..60 -> Icons.Default.VolumeDown
      else -> Icons.Default.VolumeUp
    }

  AdjustmentCard(
    valueText = displayText,
    label = stringResource(R.string.pref_player_gestures_volume),
    icon = volumeIcon,
    progress = effectivePercentage / 100f,
    overflowProgress = boostRange?.let { percentage(boostVolume, it) } ?: 0f,
    modifier = modifier,
    accentColors =
      listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primaryContainer,
      ),
    onProgressChange = onValueChange?.let { callback ->
      { callback((it * 100f).roundToInt()) }
    },
  )
}



