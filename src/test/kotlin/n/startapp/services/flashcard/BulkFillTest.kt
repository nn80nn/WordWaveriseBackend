package n.startapp.services.flashcard

import kotlin.test.Test
import kotlin.test.assertEquals

class BulkFillTest {

    @Test
    fun `loose card joins the folder being filled`() {
        assertEquals(BulkFill.Action.ADOPT, BulkFill.actionFor(cardCategory = null, wordCategory = 7))
    }

    @Test
    fun `card already in this folder is left alone`() {
        assertEquals(BulkFill.Action.SKIP, BulkFill.actionFor(cardCategory = 7, wordCategory = 7))
    }

    @Test
    fun `filling a folder never empties another one`() {
        assertEquals(BulkFill.Action.SKIP, BulkFill.actionFor(cardCategory = 3, wordCategory = 7))
    }

    @Test
    fun `filling the no-folder view does not pull cards out of folders`() {
        assertEquals(BulkFill.Action.SKIP, BulkFill.actionFor(cardCategory = 3, wordCategory = null))
    }

    @Test
    fun `both loose means nothing to do`() {
        assertEquals(BulkFill.Action.SKIP, BulkFill.actionFor(cardCategory = null, wordCategory = null))
    }
}
