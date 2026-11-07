package app.umbra.client

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Visual themes. "msn" is a colour scheme; the rest are animated layers drawn over the app. */
object Themes {
    const val NONE = "none"
    const val SNOW = "snow"
    const val RAIN = "rain"
    const val BUBBLES = "bubbles"
    const val LIGHTNING = "lightning"
    const val MSN = "msn"

    val all = listOf(
        NONE to "Off",
        SNOW to "Snow",
        RAIN to "Rain",
        BUBBLES to "Bubbles",
        LIGHTNING to "Thunderstorm (rain + lightning)",
        MSN to "MSN Messenger (2005 look)"
    )

    /** Colours of the old MSN Messenger window: sky blue, white, a green "online" accent, an orange "busy" one. */
    fun msnColors(): ColorScheme = lightColorScheme(
        primary = Color(0xFF2B6CB0),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFBFD9F2),
        onPrimaryContainer = Color(0xFF0B2A4A),
        secondary = Color(0xFF6FAE1B),
        onSecondary = Color.White,
        tertiary = Color(0xFFF08C00),
        background = Color.Transparent,         // the gradient behind shows through
        surface = Color(0xFFDCEBFA),
        onSurface = Color(0xFF16283C),
        surfaceVariant = Color(0xFFC9DDF3),
        onSurfaceVariant = Color(0xFF2F4A66),
        outline = Color(0xFF7FA6CE)
    )

    val msnBackground: Brush = Brush.verticalGradient(
        listOf(Color(0xFFFFFFFF), Color(0xFFE6F1FC), Color(0xFFBFD9F2))
    )
}

private class Particle(var x: Float, var y: Float, var speed: Float, var size: Float, var phase: Float)

private class Bolt(val points: List<Offset>, var life: Float)

/** Animated layer. It never takes touches. [amount] is 10..100 (how many particles). */
@Composable
fun ThemeOverlay(theme: String, amount: Int, modifier: Modifier = Modifier) {
    if (theme != Themes.SNOW && theme != Themes.RAIN && theme != Themes.BUBBLES && theme != Themes.LIGHTNING) return

    var tick by remember { mutableLongStateOf(0L) }
    var lastNanos by remember { mutableLongStateOf(0L) }
    val particles = remember(theme, amount) { mutableListOf<Particle>() }
    var flash by remember(theme) { mutableFloatStateOf(0f) }
    var nextBolt by remember(theme) { mutableFloatStateOf(2.5f) }
    var bolt by remember(theme) { mutableStateOf<Bolt?>(null) }
    var width by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(theme, amount) {
        lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                lastNanos = now
                val w = width
                val h = height
                if (w > 0f && h > 0f) {
                    val target = when (theme) {
                        Themes.SNOW -> 20 + amount * 2
                        Themes.BUBBLES -> 6 + amount / 3
                        else -> 40 + amount * 3          // rain, storm
                    }
                    while (particles.size < target) {
                        particles.add(spawn(theme, w, h, anywhere = true))
                    }
                    while (particles.size > target) particles.removeAt(particles.size - 1)
                    for (p in particles) {
                        when (theme) {
                            Themes.SNOW -> { p.y += p.speed * dt; p.phase += dt; p.x += sin(p.phase * 1.5f) * 18f * dt }
                            Themes.BUBBLES -> { p.y -= p.speed * dt; p.phase += dt; p.x += sin(p.phase * 2f) * 24f * dt }
                            else -> { p.y += p.speed * dt; p.x -= p.speed * 0.18f * dt }
                        }
                        val gone = if (theme == Themes.BUBBLES) p.y < -p.size * 2 else p.y > h + 20f || p.x < -40f
                        if (gone) {
                            val n = spawn(theme, w, h, anywhere = false)
                            p.x = n.x; p.y = n.y; p.speed = n.speed; p.size = n.size; p.phase = n.phase
                        }
                    }
                    if (theme == Themes.LIGHTNING) {
                        nextBolt -= dt
                        if (nextBolt <= 0f) {
                            nextBolt = 2.5f + Random.nextFloat() * 5f
                            flash = 1f
                            bolt = makeBolt(w, h)
                        }
                        flash = (flash - dt * 2.2f).coerceAtLeast(0f)
                        bolt?.let { b -> b.life -= dt; if (b.life <= 0f) bolt = null }
                    }
                }
                tick = now
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        width = size.width
        height = size.height
        tick.hashCode()      // reading the clock makes the canvas redraw every frame
        val density = this.density
        for (p in particles) {
            when (theme) {
                Themes.SNOW -> {
                    val r = p.size * density
                    drawCircle(Color(0x66336699), r + 1f * density, Offset(p.x, p.y))      // a thin edge, so the flakes also show on light screens
                    drawCircle(Color(0xEEFFFFFF), r, Offset(p.x, p.y))
                }
                Themes.BUBBLES -> {
                    val r = p.size * density
                    drawCircle(Color(0x2266B3FF), r, Offset(p.x, p.y))
                    drawCircle(Color(0x9966B3FF), r, Offset(p.x, p.y), style = Stroke(1.5f * density))
                    drawCircle(Color(0xCCFFFFFF), r * 0.22f, Offset(p.x - r * 0.4f, p.y - r * 0.4f))
                }
                else -> {
                    val len = p.size * 6f * density
                    drawLine(
                        Color(0xAA6FA8DC),
                        Offset(p.x, p.y),
                        Offset(p.x + len * 0.18f, p.y - len),
                        strokeWidth = 1.4f * density,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        if (theme == Themes.LIGHTNING) {
            if (flash > 0f) drawRect(Color.White.copy(alpha = 0.55f * flash))
            bolt?.let { b ->
                val a = (b.life / 0.35f).coerceIn(0f, 1f)
                for (i in 0 until b.points.size - 1) {
                    drawLine(Color(0xFFBBD7FF).copy(alpha = a), b.points[i], b.points[i + 1], strokeWidth = 7f * density, cap = StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = a), b.points[i], b.points[i + 1], strokeWidth = 2.5f * density, cap = StrokeCap.Round)
                }
            }
        }
    }
}

private fun spawn(theme: String, w: Float, h: Float, anywhere: Boolean): Particle {
    return when (theme) {
        Themes.SNOW -> Particle(
            Random.nextFloat() * w,
            if (anywhere) Random.nextFloat() * h else -10f,
            50f + Random.nextFloat() * 70f,
            1.5f + Random.nextFloat() * 2.5f,
            Random.nextFloat() * (2 * PI).toFloat()
        )
        Themes.BUBBLES -> Particle(
            Random.nextFloat() * w,
            if (anywhere) Random.nextFloat() * h else h + 40f,
            40f + Random.nextFloat() * 60f,
            6f + Random.nextFloat() * 16f,
            Random.nextFloat() * (2 * PI).toFloat()
        )
        else -> Particle(
            Random.nextFloat() * (w + 80f),
            if (anywhere) Random.nextFloat() * h else -20f,
            900f + Random.nextFloat() * 500f,
            1.5f + Random.nextFloat() * 2.5f,
            0f
        )
    }
}

private fun makeBolt(w: Float, h: Float): Bolt {
    val pts = mutableListOf<Offset>()
    var x = w * (0.2f + Random.nextFloat() * 0.6f)
    var y = 0f
    pts.add(Offset(x, y))
    while (y < h * 0.55f) {
        y += h * (0.04f + Random.nextFloat() * 0.05f)
        x += (Random.nextFloat() - 0.5f) * w * 0.16f
        pts.add(Offset(x, y))
    }
    return Bolt(pts, 0.35f)
}
