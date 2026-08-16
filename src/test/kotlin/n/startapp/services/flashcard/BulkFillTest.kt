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

    // ── Тот же вопрос в другой момент: слово переложили в папку ──────────────

    @Test
    fun `a card outside folders follows its word when the word is filed`() {
        // Ровно та дыра, из-за которой папка выглядела пустой, а «создать карточки из папки»
        // отвечало «они уже есть»: карточка оставалась в общих, потому что раскладывали слово.
        assertEquals(BulkFill.Action.ADOPT, BulkFill.actionFor(cardCategory = null, wordCategory = 4))
    }

    @Test
    fun `taking a word out of every folder leaves its card where it is`() {
        // Слово ушло в общие — но карточка лежит в папке, куда её положили руками.
        assertEquals(BulkFill.Action.SKIP, BulkFill.actionFor(cardCategory = 4, wordCategory = null))
    }
}
