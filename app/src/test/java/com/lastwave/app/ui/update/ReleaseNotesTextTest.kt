package com.lastwave.app.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTextTest {

    @Test
    fun `renders headings bullets and inline markup as readable text`() {
        val notes = """

            ## What's new
            - **Bold** change with `code`
              - nested [link](https://example.test/x)
            ![shot](https://example.test/shot.png)


            ---
            Plain line
        """.trimIndent()

        assertEquals(
            "What's new\n" +
                "•  Bold change with code\n" +
                "    •  nested link\n" +
                "\n" +
                "―――\n" +
                "Plain line",
            releaseNotesText(notes).text,
        )
    }

    @Test
    fun `unknown markup stays as text`() {
        assertEquals("a *b* c", releaseNotesText("a *b* c").text)
    }
}
