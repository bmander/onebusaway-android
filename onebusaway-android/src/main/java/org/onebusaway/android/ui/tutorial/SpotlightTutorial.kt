/*
 * Copyright (C) 2026 Open Transit Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.ui.tutorial

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import org.onebusaway.android.R

/**
 * One step of a spotlight tutorial: a caption (title + body, optional trailing [bodyIcon]) anchored to
 * a UI target by [id]. [id] doubles as the anchor key ([Modifier.tutorialAnchor]) and, for persisted
 * tutorials, the "already shown" preference key — so a step's target and its shown-flag stay in sync.
 */
data class TutorialStep(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:DrawableRes val bodyIcon: Int? = null,
    // When this is the last step of its sequence, the advance button reads "Finish" — unless this step
    // continues into a follow-on tutorial (e.g. the welcome map-stop step chains into the arrivals tour),
    // in which case it keeps reading "Next".
    val continuesAfter: Boolean = false,
)

/**
 * Drives a spotlight tutorial: the active step sequence, the current index, and the on-screen bounds
 * each anchored target reports. UI-only state (no persistence) — the caller decides which steps to
 * [start] (e.g. filtering out already-shown ones) and records them as shown. Targets register their
 * bounds via [Modifier.tutorialAnchor]; [TutorialOverlay] reads [current] + [boundsFor] to draw.
 */
@Stable
class TutorialState {
    var steps by mutableStateOf<List<TutorialStep>>(emptyList())
        private set
    var index by mutableStateOf(0)
        private set

    private val bounds = mutableStateMapOf<String, Rect>()

    /** The step being shown, or null when no tutorial is active. */
    val current: TutorialStep? get() = steps.getOrNull(index)

    /** True while a tutorial is running (an overlay is up, intercepting touches). */
    val active: Boolean get() = current != null

    /** True when [current] is the last step (the caption shows "done" rather than "next"). */
    val isLast: Boolean get() = index >= steps.size - 1

    /**
     * The id of the last step the user advanced *past* to finish a sequence (vs skipping it); null until
     * [consumeCompletion]. Lets a step's normal completion trigger a follow-on action — e.g. the welcome
     * map-stop step focuses its spotlighted stop so the arrivals tutorial continues.
     */
    var completedStepId by mutableStateOf<String?>(null)
        private set

    /** Begin a sequence at its first step. A no-op for an empty list. */
    fun start(steps: List<TutorialStep>) {
        if (steps.isEmpty()) return
        this.steps = steps
        index = 0
    }

    /** Advance to the next step; finishing the last step ends the tutorial and signals completion. */
    fun advance() {
        if (index < steps.size - 1) {
            index++
        } else {
            completedStepId = current?.id
            clear()
        }
    }

    /** End the tutorial immediately (skip / final dismiss) WITHOUT signalling completion. */
    fun dismiss() = clear()

    private fun clear() {
        steps = emptyList()
        index = 0
    }

    /** Acknowledge a [completedStepId] so it isn't handled again. */
    fun consumeCompletion() {
        completedStepId = null
    }

    /**
     * An anchored target reports its current on-screen bounds (root coordinates). Unchanged values are
     * dropped so a re-reporting source (e.g. the map-stop poll) doesn't needlessly recompose the overlay.
     */
    fun reportBounds(id: String, rect: Rect) {
        if (bounds[id] != rect) bounds[id] = rect
    }

    /** The last-reported bounds of the target keyed by [id], or null if it hasn't laid out yet. */
    fun boundsFor(id: String): Rect? = bounds[id]
}

@Composable
fun rememberTutorialState(): TutorialState = remember { TutorialState() }

/** An ease-out-back curve (overshoots past 1) that gives the annulus pulse its springy "bounce". */
private val SpotlightBounceEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * Provides the active [TutorialState] to deep composables so a target can anchor itself via
 * [Modifier.tutorialAnchor] without threading the state through every signature. Null when no tutorial
 * host is present (the anchor modifier then no-ops), so reused composables (e.g. the legend's EtaPill)
 * stay unaffected.
 */
val LocalTutorialState = staticCompositionLocalOf<TutorialState?> { null }

/**
 * Reports this composable's on-screen bounds to [state] under [id] so [TutorialOverlay] can spotlight
 * it. No-ops when [state] is null (no tutorial host) — safe to leave on a reused composable.
 */
fun Modifier.tutorialAnchor(state: TutorialState?, id: String): Modifier =
    if (state == null) this
    else onGloballyPositioned { state.reportBounds(id, it.boundsInRoot()) }

/**
 * The spotlight overlay for the active tutorial step: a full-screen scrim with a rounded cutout over
 * the current step's target, plus a caption card (title + body + a Next/Got-it button, and Skip until
 * the last step). Tapping anywhere (or Next) advances; Skip ends it. Renders nothing when no tutorial
 * is active, so it stops intercepting touches the moment the sequence finishes.
 *
 * Drawn as a sibling over the rest of the screen. Targets report their bounds in *root* coordinates;
 * this overlay records its own root position ([positionInRoot]) and translates each target into its
 * local space, so the spotlight lines up wherever the overlay sits in the hierarchy. Until a target
 * has laid out its bounds are null — the scrim then covers the full screen and the caption centers,
 * snapping to the cutout once the bounds arrive.
 */
@Composable
fun TutorialOverlay(state: TutorialState) {
    val step = state.current ?: return
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val target = state.boundsFor(step.id)?.translate(-overlayOrigin.x, -overlayOrigin.y)

    // A gentle, continuous "bounce" for the annulus around the cutout — an overshooting ease-in-out so
    // the ring springs out a little past its resting thickness and settles back, repeatedly.
    val pulse by rememberInfiniteTransition(label = "spotlight").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = SpotlightBounceEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "annulusPulse",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
            // The whole overlay is a tap target so a tap anywhere advances (mirrors the legacy
            // ShowcaseView's tap-to-continue); the caption's buttons consume their own taps first.
            .pointerInput(step.id) { detectTapGestures { state.advance() } }
    ) {
        // The legacy ShowcaseView look: a translucent green brand-color overlay (the same
        // tutorial_background the welcome card uses) with a transparent *circle* punched out over the
        // target. A denser, fully-opaque green annulus hugs the cutout (thickness ~half the radius) to
        // emphasize the spotlight, ringed in the darker brand color (the old sv_showcaseColor).
        val overlayColor = colorResource(R.color.tutorial_background)
        val annulusColor = overlayColor.copy(alpha = 1f)
        val ringColor = colorResource(R.color.theme_primary_variant)
        val cutoutPad = 12.dp
        val minRadius = 32.dp

        // Reused across the per-frame pulse redraws so the cutout isn't reallocated each frame.
        val cutoutPath = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            if (target == null || target.isEmpty) {
                drawRect(overlayColor)
            } else {
                val center = target.center
                val halfDiagonal =
                    (hypot(target.width.toDouble(), target.height.toDouble()) / 2.0).toFloat()
                // 1.5x the base radius for a more prominent cutout.
                val radius = maxOf(halfDiagonal + cutoutPad.toPx(), minRadius.toPx()) * 1.5f
                // Annulus thickness ~half the radius, pulsing ±~45% so the ring bounces around the cutout.
                val annulusWidth = (radius * 0.5f) * (1f + 0.45f * pulse)

                cutoutPath.rewind()
                cutoutPath.addOval(Rect(center, radius))
                // Base translucent overlay everywhere except the transparent cutout (no per-frame Path.op).
                clipPath(cutoutPath, clipOp = ClipOp.Difference) {
                    drawRect(overlayColor)
                }
                // More-opaque ring hugging the cutout: a stroke spanning the cutout edge outward.
                drawCircle(
                    color = annulusColor,
                    radius = radius + annulusWidth / 2f,
                    center = center,
                    style = Stroke(width = annulusWidth),
                )
                // The brand-color ring right at the cutout edge.
                drawCircle(
                    color = ringColor,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Keep the caption clear of the spotlight: with no target, center it; otherwise put it on the
        // opposite half of the screen from the target (our arrival-panel targets sit low, so the caption
        // rides at the top).
        val rootHeightPx = constraints.maxHeight.toFloat()
        val alignment = when {
            target == null || target.isEmpty -> Alignment.Center
            target.center.y > rootHeightPx / 2f -> Alignment.TopCenter
            else -> Alignment.BottomCenter
        }
        TutorialCaption(
            step = step,
            isLast = state.isLast,
            onNext = state::advance,
            onClose = state::dismiss,
            modifier = Modifier.align(alignment),
        )
    }
}

@Composable
private fun TutorialCaption(
    step: TutorialStep,
    isLast: Boolean,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
            .widthIn(max = 360.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        // Pass the app name as a format arg to both strings so a branded welcome title ("Welcome to
        // %1$s!") fills in; strings without a placeholder simply ignore the extra arg (white-label).
        val appName = stringResource(R.string.app_name)
        Column(Modifier.padding(start = 20.dp, top = 8.dp, end = 8.dp, bottom = 16.dp)) {
            // Title with a corner "X" that ends the whole tutorial.
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = stringResource(step.title, appName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.tutorial_button_close),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = stringResource(step.body, appName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                step.bodyIcon?.let { icon ->
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A single advance action: "Next", or "Finish" on the genuinely-final step (a last step that
            // doesn't continue into a follow-on tutorial). The corner "X" ends the tutorial outright.
            val advanceLabel = if (isLast && !step.continuesAfter) {
                R.string.tutorial_button_finish
            } else {
                R.string.pager_button_next
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onNext) { Text(stringResource(advanceLabel)) }
            }
        }
    }
}
