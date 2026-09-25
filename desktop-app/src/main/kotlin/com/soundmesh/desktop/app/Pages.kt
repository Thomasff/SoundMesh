package com.soundmesh.desktop.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.soundmesh.desktop.SinkStage

/** Which of the two this machine is being; null before one is picked. */
internal enum class Role { HOST, SINK }

/**
 * Which of the three pages the window is on - the handset's HomeRoute, worked out the same way.
 * Settings and the measuring page are not in here, as they are not in the handset's: those are
 * places somebody goes and comes back from, held beside this.
 */
internal enum class Stage { WELCOME, READY, PLAYING }

/**
 * The handset's routeOf. [steppedBack] is ← pressed on the playing page while the room plays,
 * which leaves the music on and nobody looking at it; [holding] is 进入播放 pressed, or a source
 * changed on the playing page, with nothing playing. The role wins over both: only the first page
 * can supply a missing one.
 */
internal fun stageOf(role: Role?, running: Boolean, steppedBack: Boolean, holding: Boolean): Stage = when {
    role == null -> Stage.WELCOME
    steppedBack -> Stage.READY
    running || holding -> Stage.PLAYING
    else -> Stage.READY
}

/** A sink's stages that count as the room playing here - a handset sink's running session. */
internal val SINK_RUNNING = setOf(SinkStage.OPENING_SPEAKERS, SinkStage.SYNCING, SinkStage.PLAYING, SinkStage.HOST_SILENT)

/**
 * One page of the window: the bar, whatever must stay in view under it, and a column that scrolls
 * - the handset's TopBar over its ScrollingStage. The bar is outside the scroll so the way back is
 * always where it was.
 */
@Composable
internal fun Page(
    title: String,
    onBack: (() -> Unit)?,
    onSettings: (() -> Unit)? = null,
    spacing: Dp = 6.dp,
    pinned: @Composable ColumnScope.() -> Unit = {},
    /** Held under the scrolling part, where the eye ends - the handset calibration's 上一步 / 下一步. */
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 6.dp)) {
            TopBar(title, onBack, onSettings)
            pinned()
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
        bottom?.let { Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { it() } }
    }
}

/**
 * Where you are, the way back and the way into settings - the handset's TopBar. No arrow on the
 * first page, which has nothing above it, and no 设置 on the settings page itself.
 */
@Composable
private fun TopBar(title: String, onBack: (() -> Unit)?, onSettings: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Text(
                "←",
                // A little room round it: a mouse is aimed, and one character is a small target.
                Modifier.clickable(onClick = onBack).padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (onSettings != null) {
            Text(
                say(Phrases.settings_open),
                Modifier.clickable(onClick = onSettings).padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
