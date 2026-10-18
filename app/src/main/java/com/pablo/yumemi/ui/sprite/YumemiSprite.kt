package com.pablo.yumemi.ui.sprite

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pablo.yumemi.R
import com.pablo.yumemi.domain.YumemiMood
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Frame of every sprite layer, in px. */
private const val FRAME_W = 528f
private const val FRAME_H = 576f

/** Square bust crop (head and shoulders) used for the avatar, in frame coordinates. */
private data class Bust(val cx: Float, val cy: Float, val side: Float)

private val BUST_STAND = Bust(352f, 175f, 300f)
private val BUST_SHY = Bust(380f, 230f, 300f) // the shy pose leans, so her head sits lower
/** Tighter crops: just her head, hat to chin. */
private val HEAD_STAND = Bust(360f, 118f, 270f)
private val HEAD_SHY = Bust(388f, 173f, 270f)

private data class Layers(
    @DrawableRes val body: Int,
    @DrawableRes val arms: Int, // 0 = none
    @DrawableRes val face: Int,
    @DrawableRes val ribbon: Int,
    val canTalk: Boolean,
) {
    val bust: Bust get() = if (body == R.drawable.yu_body_shy) BUST_SHY else BUST_STAND
    val head: Bust get() = if (body == R.drawable.yu_body_shy) HEAD_SHY else HEAD_STAND
}

private fun layersFor(mood: YumemiMood): Layers = when (mood) {
    YumemiMood.IDLE -> Layers(R.drawable.yu_body_stand, R.drawable.yu_arms_rest, R.drawable.yu_face_smile, R.drawable.yu_ribbon_stand_lilac, true)
    YumemiMood.HAPPY -> Layers(R.drawable.yu_body_stand, R.drawable.yu_arms_rest, R.drawable.yu_face_wink, R.drawable.yu_ribbon_stand_gold, true)
    YumemiMood.THINKING -> Layers(R.drawable.yu_body_stand, R.drawable.yu_arms_think, R.drawable.yu_face_ponder, R.drawable.yu_ribbon_stand_violet, false)
    YumemiMood.WORKING -> Layers(R.drawable.yu_body_stand, R.drawable.yu_arms_wrench, R.drawable.yu_face_focus, R.drawable.yu_ribbon_stand_teal, false)
    YumemiMood.SURPRISED -> Layers(R.drawable.yu_body_stand, R.drawable.yu_arms_rest, R.drawable.yu_face_surprised, R.drawable.yu_ribbon_stand_green, true)
    YumemiMood.ERROR -> Layers(R.drawable.yu_body_stand, R.drawable.yu_arms_rest, R.drawable.yu_face_worried, R.drawable.yu_ribbon_stand_pink, false)
    YumemiMood.SLEEPING -> Layers(R.drawable.yu_body_shy, 0, R.drawable.yu_face_sleep, R.drawable.yu_ribbon_shy_violet, false)
}

/**
 * Layered sprite of Yumemi. Portrait (528:576) unless [face] is true, in which case it is a
 * square avatar crop of her head and shoulders (just her head with [headOnly]) that follows the
 * current pose.
 */
@Composable
fun YumemiSprite(
    mood: YumemiMood,
    modifier: Modifier = Modifier,
    talking: Boolean = false,
    face: Boolean = false,
    headOnly: Boolean = false,
) {
    val happy = mood == YumemiMood.HAPPY
    // Happy alternates the wink with a bright smile so the face keeps moving.
    var winkOn by remember { mutableStateOf(true) }
    LaunchedEffect(happy) {
        winkOn = true
        while (happy) {
            delay(650)
            winkOn = !winkOn
        }
    }
    val base = layersFor(mood)
    val layers = if (happy && !winkOn) base.copy(face = R.drawable.yu_face_smile) else base

    val infinite = rememberInfiniteTransition(label = "yumemi-sprite")
    val bob by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val sway by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sway",
    )

    // Little hops and a livelier ribbon while happy.
    val hop by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(340, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "hop",
    )
    val floatT by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "float",
    )
    val lift = if (happy) hop else 0f

    // Mouth flapping while she "speaks".
    val speaking = talking && layers.canTalk
    var mouthOpen by remember { mutableStateOf(false) }
    LaunchedEffect(speaking) {
        if (speaking) {
            while (true) {
                mouthOpen = !mouthOpen
                delay(Random.nextLong(160L, 221L))
            }
        } else {
            mouthOpen = false
        }
    }

    if (face) {
        val bust = if (headOnly) layers.head else layers.bust
        BoxWithConstraints(modifier.aspectRatio(1f).clipToBounds()) {
            val s = maxWidth
            val liftPx = lift
            val k = s / bust.side // dp per frame px
            Box(
                Modifier
                    // Unbounded so the oversized stack is laid out from the top-left
                    // instead of being re-centred, then shifted onto the bust.
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset(s / 2 - k * bust.cx, s / 2 - k * bust.cy - s * 0.04f * liftPx)
                    .requiredSize(k * FRAME_W, k * FRAME_H),
            ) {
                SpriteStack(layers, mouthOpen, sway, if (happy) 3f else 1f)
            }
            if (happy) HappySparkles(floatT)
        }
    } else {
        Box(
            modifier
                .aspectRatio(FRAME_W / FRAME_H)
                .graphicsLayer { translationY = bob * 3.dp.toPx() - lift * 9.dp.toPx() },
        ) {
            SpriteStack(layers, mouthOpen, sway, if (happy) 3f else 1f)
            if (happy) HappySparkles(floatT)
        }
    }
}

@Composable
private fun SpriteStack(layers: Layers, mouthOpen: Boolean, sway: Float, swayGain: Float) {
    // Body (stand <-> shy)
    Crossfade(layers.body, Modifier.fillMaxSize(), animationSpec = tween(350), label = "body") { id ->
        SpriteLayer(id)
    }
    // Arms (optional)
    Crossfade(layers.arms, Modifier.fillMaxSize(), animationSpec = tween(350), label = "arms") { id ->
        if (id != 0) SpriteLayer(id)
    }
    // Face; talking swaps are instant, mood changes crossfade.
    Crossfade(layers.face, Modifier.fillMaxSize(), animationSpec = tween(200), label = "face") { id ->
        SpriteLayer(if (mouthOpen && id == layers.face) R.drawable.yu_face_talk else id)
    }
    // Ribbon: color crossfade + gentle sway
    Crossfade(
        layers.ribbon,
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.68f, 0.16f)
                rotationZ = sway * 1.5f * swayGain
            },
        animationSpec = tween(500),
        label = "ribbon",
    ) { id -> SpriteLayer(id) }
}

@Composable
private fun SpriteLayer(@DrawableRes id: Int) {
    Image(
        painter = painterResource(id),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}

private val SPARKLES = listOf(
    Triple("♥", 0.12f, 0.00f),
    Triple("✦", 0.78f, 0.20f),
    Triple("♥", 0.88f, 0.45f),
    Triple("✦", 0.05f, 0.62f),
    Triple("★", 0.70f, 0.80f),
)

/** Hearts and stars drifting up around her while she is happy. */
@Composable
private fun HappySparkles(t: Float) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        SPARKLES.forEachIndexed { i, (glyph, fx, phase) ->
            val p = (t + phase) % 1f
            val alpha = (1f - p).coerceIn(0f, 1f) * (p * 6f).coerceAtMost(1f)
            Text(
                glyph,
                color = (if (glyph == "♥") Color(0xFFFF8FB5) else Color(0xFFFFD36B)).copy(alpha = alpha),
                fontSize = (if (i % 2 == 0) 16 else 13).sp,
                modifier = Modifier.offset(maxWidth * fx, maxHeight * (0.7f - 0.6f * p)),
            )
        }
    }
}
