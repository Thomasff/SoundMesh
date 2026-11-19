package com.soundmesh.product

import java.net.URLEncoder

/**
 * The address 反馈与建议 on the about block opens: the mail app, with [address] and [subject]
 * filled in and the body empty but for [device] at its foot, so a report says what it ran on
 * without anybody having to ask.
 */
fun feedbackMailto(address: String, subject: String, device: String): String =
    "mailto:$address?subject=${uriEncode(subject)}&body=${uriEncode("\n\n\n---\n$device")}"

// URLEncoder is form encoding, which writes a space as '+'; a mailto address is read as a URI.
private fun uriEncode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
