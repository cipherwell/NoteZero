package com.cipherwell.notezero.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backing out of the editor trashes only notes that never got content. Attachments are
 * content: a note with a drawing/photo/file and no text must survive (device QA finding).
 */
class EditorLeaveTest {

    private fun shouldTrash(
        title: String = "",
        body: String = "",
        attachmentCount: Int = 0,
        loading: Boolean = false,
        missing: Boolean = false,
        sessionCleared: Boolean = false,
    ) = shouldTrashOnLeave(title, body, attachmentCount, loading, missing, sessionCleared)

    @Test
    fun `brand-new blank note is trashed on leave`() {
        assertTrue(shouldTrash())
    }

    @Test
    fun `note with a title is kept`() {
        assertFalse(shouldTrash(title = "Shopping"))
    }

    @Test
    fun `note with a body is kept`() {
        assertFalse(shouldTrash(body = "milk"))
    }

    @Test
    fun `attachment-only note is kept`() {
        assertFalse(shouldTrash(attachmentCount = 1))
        assertFalse(shouldTrash(title = "", body = "", attachmentCount = 3))
    }

    @Test
    fun `loading, missing or cleared-session notes are never touched`() {
        assertFalse(shouldTrash(loading = true))
        assertFalse(shouldTrash(missing = true))
        assertFalse(shouldTrash(sessionCleared = true))
    }
}
