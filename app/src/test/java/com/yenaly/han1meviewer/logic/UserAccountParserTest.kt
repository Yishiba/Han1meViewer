package com.yenaly.han1meviewer.logic

import com.yenaly.han1meviewer.logic.state.WebsiteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAccountParserTest {
    @Test
    fun parsesReadOnlyEmailFromCurrentProfileMarkup() {
        val account = Parser.userAccountPage(profile("<input id='email' value='user@example.com' disabled>"))
            as WebsiteState.Success

        assertEquals("user@example.com", account.info.email)
        assertFalse(account.info.emailEditable)
    }

    @Test
    fun keepsLegacyEditableEmailSupport() {
        val account = Parser.userAccountPage(profile("<input name='email' value='old@example.com'>"))
            as WebsiteState.Success

        assertEquals("old@example.com", account.info.email)
        assertTrue(account.info.emailEditable)
    }

    private fun profile(emailField: String) = """
        <html><head><meta name="csrf-token" content="token"></head><body>
          <img id="playlist-avatar" src="https://example.com/avatar.jpg">
          <input name="name" value="Tester">
          $emailField
          <div class="profile-sub-stats-id">ID: 123</div>
          <div class="profile-sub-stats-new-line">1 subscribers, 2 videos</div>
        </body></html>
    """.trimIndent()
}
