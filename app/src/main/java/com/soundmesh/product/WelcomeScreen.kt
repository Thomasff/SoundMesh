package com.soundmesh.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soundmesh.probe.R

/**
 * Stage one: nobody has picked a role yet.
 *
 * The one screen in the app drawn loose rather than dense - two choices, one of which has to be
 * made before anything else exists, so they get the room. Every other stage is a board.
 *
 * What used to be here was a "要准备什么" list: two phones, both with the app, both on one WiFi.
 * Those are facts about the room rather than instructions for this screen, and the status screen
 * states all three of them as they actually stand - how many handsets have joined, which network
 * the code is for. A list that says them here as prerequisites is a manual in front of a door.
 */
@Composable
fun WelcomeScreen(state: HomeState, actions: HomeActions) {
    Column(modifier = Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.welcome_what),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            lineHeight = 30.sp
        )
        Note(stringResource(R.string.welcome_role))
    }
    RolePicker(actions)
    Box(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
    // What this phone is called on everybody else's screen, which is the one thing about it a
    // person needs before they walk to another handset. It is also the only place it is said
    // before a room exists.
    Note(stringResource(R.string.welcome_self, state.calledHere.ifEmpty { stringResource(R.string.roster_self) }))
    Note(stringResource(R.string.welcome_role_later))
}

/**
 * The two roles, each a box with its own sentence.
 *
 * Boxes rather than buttons: the sentence under each is what people actually read, and a Material
 * button cannot carry one. The whole box is the tap.
 */
@Composable
internal fun RolePicker(actions: HomeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RoleBox(R.string.role_host, R.string.role_host_hint) { actions.pickRole(Role.HOST) }
        RoleBox(R.string.role_sink, R.string.role_sink_hint) { actions.pickRole(Role.SINK) }
    }
}

@Composable
private fun RoleBox(name: Int, hint: Int, onClick: () -> Unit) {
    Box(modifier = Modifier.clickable(onClick = onClick)) {
        Framed {
            BoxTitle(stringResource(name), strong = true)
            Note(stringResource(hint))
        }
    }
}
