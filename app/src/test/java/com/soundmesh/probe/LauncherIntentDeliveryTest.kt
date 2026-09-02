package com.soundmesh.probe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIntentDeliveryTest {
    /**
     * A backgrounded standard-mode task is only moved to the front, so a repeated case intent
     * never reaches onNewIntent. singleTask keeps one instance and always delivers the intent.
     */
    @Test
    fun launcherActivityUsesSingleTaskSoRepeatedCaseIntentsAreDelivered() {
        val quote = '"'
        val manifest = File("src/main/AndroidManifest.xml").readText(Charsets.UTF_8)
        val activity = Regex("""<activity[\s\S]*?</activity>""").find(manifest)?.value.orEmpty()

        assertTrue("MainActivity declaration not found", activity.contains("android:name=${quote}.MainActivity${quote}"))
        assertTrue("MainActivity must be singleTask but was: $activity", activity.contains("android:launchMode=${quote}singleTask${quote}"))
    }
}
