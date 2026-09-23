package com.soundmesh.desktop.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasesTest {
    private val placeholder = Regex("""%(\d+\$)?[-.0-9]*[a-z%]""")

    /**
     * An English line written by hand that takes its arguments differently from the Chinese one
     * compiles, and throws only when that line is drawn - in the one language nobody tested in.
     */
    @Test
    fun bothLanguagesTakeTheSameArguments() {
        val mismatched = Phrases.all.filter { (_, phrase) ->
            placeholder.findAll(phrase.zh).map { it.value }.sorted().toList() !=
                placeholder.findAll(phrase.en).map { it.value }.sorted().toList()
        }.keys
        assertTrue("different arguments in the two languages: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun theHandsetsEscapesAreReadAsTheHandsetReadsThem() {
        assertEquals("This phone's output lead", Phrases.goto_self.en)
        // Quoted to keep its space, which would otherwise be trimmed away.
        assertEquals(", ", Phrases.room_volume_name_join.en)
        assertEquals("、", Phrases.room_volume_name_join.zh)
        assertTrue(Phrases.standby_notification.zh.contains('\n'))
    }

    @Test
    fun aLineIsFormattedOnlyWhenGivenSomethingToPutInIt() {
        assertEquals("%1\$d%%", Phrases.room_knob_percent.of(english = false))
        assertEquals("40%", Phrases.room_knob_percent.of(english = false, 40))
        assertEquals("3 in the room", Phrases.pc_room_count.of(english = true, 3))
    }
}
