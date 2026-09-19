package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every notification this app posts wears this app's mark.
 *
 * Until 2026-09-19 all four wore stock Android icons - a speaker, a microphone, a play triangle -
 * picked one at a time by whoever wrote each service. In a shade full of other apps' notifications
 * the small icon is the only thing that says which app a line came from, so four borrowed icons
 * are four notifications from nobody in particular. Reported from a sink's shade: 「没显示我们软件
 * 的图标，我现在看到的是一个喇叭的图标」.
 *
 * Read as source because none of it can be run here: a Notification needs a Context, and what the
 * status bar does with a drawable is not something the JVM has an opinion about.
 */
class NotificationMarkTest {
    private fun source(path: String) = File(path).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private val services = listOf(
        "src/main/java/com/soundmesh/product/StandbyService.kt",
        "src/main/java/com/soundmesh/session/SessionService.kt",
        "src/main/java/com/soundmesh/probe/CaptureForegroundService.kt",
        "src/main/java/com/soundmesh/probe/sync/SyncProjectionService.kt"
    )

    @Test
    fun noNotificationBorrowsAnIconFromAndroid() {
        for (path in services) {
            val text = source(path)
            val icons = Regex("setSmallIcon\\(([^)]*)\\)").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue("$path posts a notification with no small icon at all", icons.isNotEmpty())
            for (icon in icons) {
                assertEquals("$path wears somebody else's icon", "R.drawable.ic_notification", icon)
            }
        }
    }

    /**
     * The mark is a silhouette, and nothing else will do.
     *
     * The system paints a small icon white or dark to suit what is behind it and throws every
     * colour in the file away, so a drawable that carries the launcher icon's dark ground would
     * arrive as a solid block. A `tint` would be worse: it fixes a colour the status bar is then
     * free to overpaint, which reads as correct on whichever theme it was drawn against.
     */
    @Test
    fun theMarkIsDrawnAsASilhouetteRatherThanAsTheLauncherIcon() {
        val mark = source("src/main/res/drawable/ic_notification.xml")
        assertTrue("the mark is not white", mark.contains("android:fillColor=\"@android:color/white\""))
        assertTrue("the mark is not on the 24dp grid", mark.contains("android:viewportWidth=\"24\""))
        assertTrue("the mark carries a tint the status bar will overpaint", !mark.contains("android:tint"))
    }
}
