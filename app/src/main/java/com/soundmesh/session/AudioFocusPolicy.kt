package com.soundmesh.session

/**
 * Whether a session should take the audio focus away from everything else on the handset.
 *
 * Every session did, until one of them was asked to capture. A capturing host is not producing
 * content of its own - it is relaying content that belongs to another app on the same phone, and
 * that app needs the focus to go on playing. Taking AUDIOFOCUS_GAIN from it pauses it, the capture
 * then reads silence, and both handsets play that silence perfectly: the host's output ran for
 * fifty seconds writing nothing but zeros while every media player on the device sat paused.
 *
 * Nothing is lost by not asking. The focus exists so two apps do not talk over each other, and the
 * one app this could collide with is the one whose sound we are carrying.
 */
fun takesAudioFocus(host: Boolean, capturing: Boolean): Boolean = !(host && capturing)
