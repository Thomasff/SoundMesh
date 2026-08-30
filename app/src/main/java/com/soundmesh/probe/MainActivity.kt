package com.soundmesh.probe

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

object ProbeIdentity {
    const val APPLICATION_ID = "com.soundmesh.probe"
}

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(R.string.probe_ready) })
    }
}
