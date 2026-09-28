package com.soundmesh.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    private val repo = "https://github.com/Thomasff/SoundMesh"
    private val api = "https://api.github.com/repos/Thomasff/SoundMesh/releases/latest"

    /** The top of a real answer, with the author's and an asset's own fields after the tag. */
    private fun release(tag: String) = """
        {"url":"$api","html_url":"$repo/releases/tag/$tag","id":1,
         "author":{"login":"Thomasff","html_url":"https://github.com/Thomasff"},
         "tag_name":"$tag","name":"$tag",
         "assets":[{"name":"SoundMesh-$tag-windows-x64.zip","html_url":"x"}]}
    """.trimIndent()

    @Test
    fun theApiAddressIsReadOffTheRepositoryPage() {
        assertEquals(api, latestReleaseApi(repo))
        assertEquals(api, latestReleaseApi("$repo/"))
        assertNull(latestReleaseApi("https://gitlab.com/Thomasff/SoundMesh"))
        assertNull(latestReleaseApi(""))
    }

    @Test
    fun theTagIsFoundAmongTheOtherFields() {
        assertEquals("v0.3.0", tagOf(release("v0.3.0")))
        assertNull(tagOf("""{"message":"Not Found"}"""))
    }

    /** Compared part by part as numbers: as text, 0.10.0 sorts before 0.9.0. */
    @Test
    fun newerIsNumericPartByPart() {
        assertTrue(isNewer("v0.10.0", "0.9.0"))
        assertTrue(isNewer("v0.3.0", "0.2.9"))
        assertTrue(isNewer("v1.0", "0.9.9"))
        assertFalse(isNewer("v0.3.0", "0.3.0"))
        assertFalse(isNewer("v0.3", "0.3.0"))
        assertFalse(isNewer("v0.2.0", "0.3.0"))
    }

    @Test
    fun aNewerReleaseAnswersWithItsOwnPage() {
        assertEquals(
            UpdateAnswer.Newer("v0.3.0", "$repo/releases/tag/v0.3.0"),
            answerUpdate("$repo/", "0.2.0") { if (it == api) release("v0.3.0") else null }
        )
    }

    @Test
    fun theSameOrAnOlderReleaseIsUpToDate() {
        assertEquals(UpdateAnswer.UpToDate, answerUpdate(repo, "0.3.0") { release("v0.3.0") })
        assertEquals(UpdateAnswer.UpToDate, answerUpdate(repo, "0.4.0") { release("v0.3.0") })
    }

    /** No answer, an answer without a tag, and a fetch that throws all fail the same way. */
    @Test
    fun anythingButATagIsAFailure() {
        assertEquals(UpdateAnswer.Failed, answerUpdate(repo, "0.2.0") { null })
        assertEquals(UpdateAnswer.Failed, answerUpdate(repo, "0.2.0") { """{"message":"Not Found"}""" })
        assertEquals(UpdateAnswer.Failed, answerUpdate(repo, "0.2.0") { throw java.io.IOException("offline") })
        assertEquals(UpdateAnswer.Failed, answerUpdate("", "0.2.0") { release("v0.3.0") })
    }
}
