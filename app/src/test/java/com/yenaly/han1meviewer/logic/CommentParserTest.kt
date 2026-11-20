package com.yenaly.han1meviewer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        """)

        assertEquals(listOf("101", "102", "103", "104"), comments.videoComment.map { it.id })
        assertEquals(listOf("First", "Second", "Third", "Fourth"), comments.videoComment.map { it.content })
        assertFalse(comments.hasMoreComments)
    }

    @Test
    fun parsesPaginationMetadataAndNextBatch() {
        val first = Parser.parseCommentsHtml("""
            <div id="comment-start">${commentBlock("101", "First")}</div>
            <div id="comment-scroll-sentinel" data-has-more="1" data-next-offset="100"></div>
        """)
        assertTrue(first.hasMoreComments)
        assertEquals(100, first.nextCommentOffset)

        val next = Parser.parseCommentsPageHtml(commentBlock("102", "Second"), false, null)
        assertEquals(listOf("102"), next.videoComment.map { it.id })
        assertFalse(next.hasMoreComments)
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
