package com.lastwave.app.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoinSupportPromptTest {

    @Test
    fun defaultsToNotDismissedOnFreshInstall() {
        val settings = MiscSettings()
        assertThat(settings.hasDismissedJoinSupportPrompt).isFalse()
    }

    @Test
    fun canBeMarkedDismissed() {
        val settings = MiscSettings(hasDismissedJoinSupportPrompt = true)
        assertThat(settings.hasDismissedJoinSupportPrompt).isTrue()
    }
}
