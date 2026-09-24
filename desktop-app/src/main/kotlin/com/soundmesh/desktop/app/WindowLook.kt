package com.soundmesh.desktop.app

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import javax.imageio.ImageIO

/**
 * The handset's launcher icon on every window, the taskbar and Alt+Tab - asset/master/icon-1024.png,
 * made smaller once, ahead of time, into the sizes Windows asks for (asset/windows, which also
 * holds the .ico the installer and the exe carry). All of them rather than one: Windows picks the
 * nearest for each place, and one image scaled down to 16 pixels by AWT is a smudge.
 */
internal object AppIcon {
    private val SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 256)

    val images: List<BufferedImage> by lazy {
        SIZES.mapNotNull { size ->
            AppIcon::class.java.getResourceAsStream("/icon/soundmesh-$size.png")?.use { ImageIO.read(it) }
        }
    }

    /** For Compose's own icon slot, which takes one picture; [dress] then hands AWT all of them. */
    val painter: Painter? by lazy { images.lastOrNull()?.let { BitmapPainter(it.toComposeImageBitmap()) } }

    fun dress(window: Window) {
        if (images.isNotEmpty()) window.iconImages = images
    }
}

/**
 * The title bar in the window's own ground and ink, so the window reads as one piece rather than
 * a page under a white strip. Windows' own bar, recoloured, rather than one drawn by hand: it keeps
 * dragging, snapping to half the screen, the maximise menu and the three buttons as every other
 * window has them.
 *
 * The colours are Windows 11's (DWMWA_CAPTION_COLOR and DWMWA_TEXT_COLOR). Windows 10 refuses
 * both and keeps only the dark-mode switch, which turns the bar and its buttons dark but not our
 * dark; nothing else is lost there.
 */
internal object TitleBar {
    private val SET = runCatching {
        Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("dwmapi.dll", Arena.global()).find("DwmSetWindowAttribute").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT
            )
        )
    }.getOrNull()
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    /**
     * Once the window has a handle: the first composition can run before it does, and a handle of
     * 0 would recolour nothing and say nothing.
     */
    fun paint(window: ComposeWindow, ground: Color, ink: Color, dark: Boolean) {
        // Also what shows for a moment along an edge being dragged out, before Compose fills it.
        window.background = java.awt.Color(ground.toArgb())
        if (window.windowHandle != 0L) {
            paint(window.windowHandle, ground, ink, dark)
            return
        }
        window.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                window.removeWindowListener(this)
                paint(window.windowHandle, ground, ink, dark)
            }
        })
    }

    private fun paint(hwnd: Long, ground: Color, ink: Color, dark: Boolean) {
        val set = SET ?: return
        if (hwnd == 0L) return
        Arena.ofConfined().use { arena ->
            val cell = arena.allocate(ValueLayout.JAVA_INT)
            fun put(attribute: Int, value: Int) {
                cell.set(ValueLayout.JAVA_INT, 0L, value)
                runCatching { set.invokeWithArguments(MemorySegment.ofAddress(hwnd), attribute, cell, 4) }
            }
            put(DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
            put(DWMWA_CAPTION_COLOR, colorRef(ground))
            put(DWMWA_TEXT_COLOR, colorRef(ink))
        }
    }

    /** Windows' COLORREF: 0x00BBGGRR. */
    private fun colorRef(colour: Color): Int {
        val argb = colour.toArgb()
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
