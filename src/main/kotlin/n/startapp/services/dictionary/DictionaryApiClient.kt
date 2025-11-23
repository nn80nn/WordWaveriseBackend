package n.startapp.services.dictionary

import n.startapp.models.dictionary.SourcedWordData

/**
 * Interface for dictionary API clients
 */
interface DictionaryApiClient {
    /**
     * Fetch word data from this source
     * @param word The word to look up
     * @return SourcedWordData or null if word not found or error occurs
     */
    suspend fun fetchWordData(word: String): SourcedWordData?

    /**
     * Name of the source for logging and debugging
     */
    val sourceName: String
}
