package com.soundmesh.product

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/*
 * The two pieces of Look.kt that take their words by resource id. Look.kt itself is compiled into
 * the computer's window too (see app/build.gradle.kts), which has no resources, so there they take
 * a string and here the handset's screens go on naming the string they mean.
 */

/** [Label] with its title from the handset's strings. */
@Composable
fun Label(@StringRes title: Int, trailing: String? = null, onTrailing: (() -> Unit)? = null) =
    Label(stringResource(title), trailing, onTrailing)

/** [PageBar] with its title from the handset's strings. */
@Composable
fun PageBar(@StringRes title: Int, onBack: () -> Unit) = PageBar(stringResource(title), onBack)
