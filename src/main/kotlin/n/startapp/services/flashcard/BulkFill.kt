package n.startapp.services.flashcard

/**
 * What filling a folder should do with a word that already has a card somewhere.
 *
 * The rule lives apart from the SQL because it is the whole behaviour of the feature: everything
 * around it is a loop and an UPDATE.
 */
object BulkFill {

    enum class Action { ADOPT, SKIP }

    /**
     * [cardCategory] — folder the existing card sits in, `null` = outside any folder.
     * [wordCategory] — folder the saved word is filed under, `null` = outside any folder.
     *
     * A loose card is adopted: it exists only because the word was starred before folders were
     * involved, and treating it as "already done" leaves the folder empty with the button
     * insisting there is nothing to do.
     *
     * A card already inside some other real folder is left where it is — that placement is a
     * decision somebody made (the card can be moved on its own), and filling one folder has no
     * business emptying another.
     */
    fun actionFor(cardCategory: Int?, wordCategory: Int?): Action =
        if (cardCategory == null && wordCategory != null) Action.ADOPT else Action.SKIP
}
