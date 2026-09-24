package com.soundmesh.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import com.soundmesh.core.BadgeHues
import com.soundmesh.desktop.SinkStage
import com.soundmesh.product.BadgeEdges
import com.soundmesh.product.rememberEdgeGlow
import com.soundmesh.product.soundMeshColours
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * What the edge light is saying - the handset HomeScreen's edge: this machine's colour, whether it
 * is a sink that has dropped off its host, and whether it is playing. Said by whichever pane is
 * open, from the status it already reads; [sink] says which session's loudness it follows.
 */
internal data class EdgeLook(val place: Int?, val disconnected: Boolean, val playing: Boolean, val sink: Boolean)

/** The sink stages in which it is on its host's list, as a handset standing by is. */
internal val ON_THE_LIST = setOf(
    SinkStage.STANDING_BY, SinkStage.OPENING_SPEAKERS, SinkStage.SYNCING, SinkStage.PLAYING,
    SinkStage.HOST_SILENT, SinkStage.MEASURING
)

/**
 * The edge's colour, or null while this machine has none. Dimmed rather than only stilled for a
 * sink off its host, as on the handset: from across the room it should read as grey, not as a
 * quieter version of its own colour.
 */
internal fun EdgeLook?.colour(): Color? =
    this?.place?.let { BadgeHues.argb.getOrNull(it) }?.let { Color(it) }
        ?.let { if (disconnected) it.copy(alpha = 0.3f) else it }

/**
 * The edge light round the whole of [screen] rather than round the window - 设置's 整个屏幕.
 *
 * Four windows, one along each side and each as deep as the light, rather than one window over the
 * screen: every frame of a see-through window is handed to the desktop whole, and four strips are a
 * few per cent of the pixels of a screen. Each draws the ring laid out for the whole screen and
 * shows its own side of it, so the waves have the screen's perimeter to run round, as on the
 * handset.
 *
 * Each strip is always on top, never takes the focus, stays out of the taskbar and Alt+Tab (a tool
 * window), and lets the mouse through to whatever is under it - see [ClickThrough]. Over the
 * taskbar too: the screen's edge is the screen's edge. What it cannot go over is what Windows puts
 * above every window - a full-screen game, the lock screen, a UAC prompt.
 *
 * Each strip keeps its own [rememberEdgeGlow], on its own frame clock: one borrowed from the main
 * window would stop when that window is minimised, which is exactly when this is wanted.
 */
@Composable
internal fun ScreenEdges(look: EdgeLook?, loudness: () -> Float, screen: Rectangle) {
    val colour = look.colour() ?: return
    for (side in Side.entries) {
        val strip = side.of(screen)
        Window(
            create = {
                ComposeWindow().apply {
                    isUndecorated = true
                    isTransparent = true
                    // A tool window: no taskbar button and no place in Alt+Tab.
                    type = java.awt.Window.Type.UTILITY
                    focusableWindowState = false
                    isAlwaysOnTop = true
                    bounds = strip
                    addWindowListener(object : WindowAdapter() {
                        override fun windowOpened(e: WindowEvent) = ClickThrough.set(windowHandle)
                    })
                }
            },
            dispose = ComposeWindow::dispose,
            update = { it.bounds = strip }
        ) {
            val glow = rememberEdgeGlow(
                lit = true,
                disconnected = look?.disconnected == true,
                playing = look?.playing == true,
                everyNanos = EDGE_EVERY_NANOS,
                loudness = loudness
            )
            // The dark scheme only for how the waves are laid down: added as light, crests towards
            // white. There is no ground of ours under a strip, only whatever is on the screen.
            MaterialTheme(colorScheme = soundMeshColours(dark = true)) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .offset((screen.x - strip.x).dp, (screen.y - strip.y).dp)
                            .requiredSize(screen.width.dp, screen.height.dp)
                    ) {
                        BadgeEdges(colour, glow, round = 0f)
                    }
                }
            }
        }
    }
}

/**
 * The four strips. AWT's units on this machine are the window's own dp, so the depth is in both.
 * The two long sides take the corners; the light there is the flat bands' anyway, and the strips
 * are see-through where nothing is drawn.
 */
private enum class Side {
    TOP, BOTTOM, LEFT, RIGHT;

    fun of(s: Rectangle): Rectangle = when (this) {
        TOP -> Rectangle(s.x, s.y, s.width, DEPTH)
        BOTTOM -> Rectangle(s.x, s.y + s.height - DEPTH, s.width, DEPTH)
        LEFT -> Rectangle(s.x, s.y, DEPTH, s.height)
        RIGHT -> Rectangle(s.x + s.width - DEPTH, s.y, DEPTH, s.height)
    }
}

/**
 * How long the light sleeps between moves on a computer, about 30 a second - see rememberEdgeGlow's
 * everyNanos. Measured on 09-24 on this laptop's 165 Hz screen, Direct3D, the light following a
 * made-up song, SoundMesh's own process:
 *
 *     every frame (163 a second)   window 40-42%   whole screen 94-99% of a core
 *     15 ms (61 a second)          window 23-24%   whole screen 40%
 *     30 ms (31 a second)          window 13-18%   whole screen 32%
 *     the window still             1-2%
 *
 * What is left at 30 is mostly not the waves: one dot moving in the same window cost 10-13%, and
 * OpenGL and the software renderer did no better. A Compose window that moves at all costs about
 * that here. A 24-core machine reads a core's 15% as well under 1% in Task Manager.
 *
 * The waves' speed is in seconds, so a slower pace only makes each step bigger. Not tried: slower
 * still while standing by, where nothing follows the music.
 */
internal const val EDGE_EVERY_NANOS = 30_000_000L

/** EdgeWave's EDGE_NODE, the depth the bands are drawn in: 24dp. */
private const val DEPTH = 24

/**
 * Makes a window let the mouse through. A see-through window is already layered, and a layered
 * window that is also WS_EX_TRANSPARENT is skipped when Windows works out what is under the
 * pointer - checked on 09-24 with WindowFromPoint, which answered the strip before and the window
 * under it after.
 */
private object ClickThrough {
    private val linker = Linker.nativeLinker()
    private val user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global())
    private val GET = linker.downcallHandle(
        user32.find("GetWindowLongPtrW").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    )
    private val SET = linker.downcallHandle(
        user32.find("SetWindowLongPtrW").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
    )
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TRANSPARENT = 0x20L
    private const val WS_EX_LAYERED = 0x80000L

    fun set(hwnd: Long) {
        val window = MemorySegment.ofAddress(hwnd)
        val style = GET.invokeWithArguments(window, GWL_EXSTYLE) as Long
        SET.invokeWithArguments(window, GWL_EXSTYLE, style or WS_EX_TRANSPARENT or WS_EX_LAYERED)
    }
}
