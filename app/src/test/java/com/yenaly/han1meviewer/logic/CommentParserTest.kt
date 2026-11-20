package com.yenaly.han1meviewer.logic

import com.yenaly.han1meviewer.logic.state.WebsiteState
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentParserTest {
    @Test
    fun parsesEachNewCommentBlockAndItsReplyId() {
        val comments = Parser.parseCommentsHtml("""
            <div id="comment-start">
              ${commentBlock("101", "First")}
              ${commentBlock("102", "Second")}
              ${commentBlock("103", "Third")}
              ${commentBlock("104", "Fourth")}
            </div>
        """) as WebsiteState.Success

        assertEquals(listOf("101", "102", "103", "104"), comments.info.videoComment.map { it.id })
        assertEquals(listOf("First", "Second", "Third", "Fourth"), comments.info.videoComment.map { it.content })
    }

    private fun commentBlock(id: String, content: String) = """
        <div class="comment-item-block" data-comment-id="$id">
          <img src="https://example.com/avatar.jpg">
          <div class="comment-index-text"><a>User $id</a><span>Today</span></div>
          <div class="comment-index-text">$content</div>
          <div id="comment-like-form-wrapper"><span style="display:block">Likes</span><span style="display:block">1</span></div>
          <input id="foreign_id" value="$id">
          <input name="comment-likes-count" value="1">
          <input name="comment-likes-sum" value="1">
        </div>
    """.trimIndent()

}
