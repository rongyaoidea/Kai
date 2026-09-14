package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.ui.handCursor
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.heartbeat_fab_delete
import kai.composeapp.generated.resources.heartbeat_fab_open
import kai.composeapp.generated.resources.heartbeat_fab_running
import kai.composeapp.generated.resources.heartbeat_fab_unread
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Floating entry point for the dedicated heartbeat conversation. Replaces the old
 * top-of-chat banner — the conversation no longer shows up in the chat history list,
 * so this button is the main way in.
 *
 * Three visual states:
 * - idle — quiet, no continuous animation
 * - unread — a spring pop when the report arrives plus a repeating "heartbeat"
 *   double-beat scale and an expanding halo, a dot badge on the corner
 * - running — a gradient arc spins around the button while the heartbeat call is
 *   in flight (driven by [TaskScheduler.isHeartbeatRunning])
 *
 * Swipe left past a threshold or long-press to delete the conversation (undo can be
 * offered by the caller through the shared pending-deletion snackbar).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HeartbeatFab(
    visible: Boolean,
    hasUnread: Boolean,
    isRunning: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val dragOffset = remember { Animatable(0f) }
    val deleteThreshold = with(density) { 64.dp.toPx() }
    // Swipe toward the "end" side of the layout to delete: left in LTR, right in RTL.
    val swipeSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f

    // Cardiac-cycle scale: two beats (systole) then a rest (diastole).
    val beatTransition = rememberInfiniteTransition(label = "heartbeat-beat")
    val beatScale by beatTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1600
                1.12f at 90
                1f at 180
                1.07f at 270
                1f at 400
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "heartbeat-beat-scale",
    )

    // Expanding halo ring, one per cardiac cycle.
    val haloTransition = rememberInfiniteTransition(label = "heartbeat-halo")
    val haloProgress by haloTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "heartbeat-halo-progress",
    )

    // Running-state arc rotation.
    val spinTransition = rememberInfiniteTransition(label = "heartbeat-spin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "heartbeat-spin-angle",
    )

    // One-shot spring pop when a new report arrives.
    val popScale = remember { Animatable(1f) }
    var wasUnread by remember { mutableStateOf(false) }
    LaunchedEffect(hasUnread) {
        if (hasUnread && !wasUnread) {
            popScale.snapTo(0.78f)
            popScale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
        }
        wasUnread = hasUnread
    }

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "heartbeat-press-scale",
    )
    val scale = popScale.value * pressScale * (if (hasUnread) beatScale else 1f)

    val deleteProgress = (dragOffset.value * swipeSign / deleteThreshold).coerceIn(0f, 1f)
    val baseContainer = if (hasUnread) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val containerColor = lerp(baseContainer, MaterialTheme.colorScheme.errorContainer, deleteProgress * 0.8f)
    val baseIconTint = if (hasUnread) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconTint = lerp(baseIconTint, MaterialTheme.colorScheme.onErrorContainer, deleteProgress)
    val haloColor = MaterialTheme.colorScheme.tertiary
    val ringColor = MaterialTheme.colorScheme.primary
    val indication = LocalIndication.current

    val dragState = rememberDraggableState { delta ->
        scope.launch {
            // Only the delete direction moves the button.
            val next = dragOffset.value + delta
            dragOffset.snapTo(if (swipeSign < 0f) next.coerceAtMost(0f) else next.coerceAtLeast(0f))
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(initialScale = 0.6f, transformOrigin = TransformOrigin(1f, 1f)) + fadeIn(),
        exit = scaleOut(targetScale = 0.6f, transformOrigin = TransformOrigin(1f, 1f)) + fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
                .size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val buttonRadius = 26.dp.toPx()
                if (hasUnread && !isRunning) {
                    val haloRadius = buttonRadius * (1f + 0.55f * haloProgress)
                    drawCircle(
                        color = haloColor.copy(alpha = 0.4f * (1f - haloProgress)),
                        radius = haloRadius,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                if (isRunning) {
                    val ringRadius = size.minDimension / 2 - 2.dp.toPx()
                    rotate(spinAngle) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(Color.Transparent, ringColor),
                            ),
                            startAngle = 0f,
                            sweepAngle = 280f,
                            useCenter = false,
                            topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                            size = Size(ringRadius * 2, ringRadius * 2),
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
            }

            val contentDescription = when {
                isRunning -> stringResource(Res.string.heartbeat_fab_running)
                hasUnread -> stringResource(Res.string.heartbeat_fab_unread)
                else -> stringResource(Res.string.heartbeat_fab_open)
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(containerColor)
                    .handCursor()
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = indication,
                        onLongClickLabel = stringResource(Res.string.heartbeat_fab_delete),
                        onLongClick = onDelete,
                        onClick = onOpen,
                    )
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = {
                            if (dragOffset.value * swipeSign >= deleteThreshold) {
                                dragOffset.animateTo(
                                    targetValue = swipeSign * with(density) { 96.dp.toPx() },
                                    animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                                )
                                onDelete()
                                dragOffset.snapTo(0f)
                            } else {
                                dragOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                )
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MonitorHeart,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp),
                )
                if (hasUnread) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 7.dp, end = 7.dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                }
            }
        }
    }
}
