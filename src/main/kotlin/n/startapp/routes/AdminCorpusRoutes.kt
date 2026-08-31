package n.startapp.routes

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import n.startapp.models.ApiResponse
import n.startapp.models.admin.AddQueueWordsRequest
import n.startapp.models.admin.AddQueueWordsResult
import n.startapp.models.admin.WarmupQueueItem
import n.startapp.models.admin.WarmupQueueView
import n.startapp.repositories.LexicalEntryRepository
import n.startapp.repositories.WarmupQueueRepository
import n.startapp.services.warmup.WarmupService

/**
 * What the corpus contains, what it cost, and what is still queued to be built.
 *
 * Separate from the user-facing lookup routes because these answer questions about the corpus
 * rather than serving it, and separate from [adminRoutes] because that file is about accounts.
 */
fun Route.adminCorpusRoutes(
    repository: LexicalEntryRepository,
    warmupService: WarmupService,
    queue: WarmupQueueRepository,
    savedWords: n.startapp.repositories.SavedWordRepository
) {
    route("/api/admin/corpus") {

        get("/stats") {
            if (call.rejectedAsNonAdmin()) return@get
            val stats = repository.stats(
                warmupWords = warmupService.bundledWords(),
                queueWords = queue.words()
            )
            call.respond(ApiResponse.success(stats))
        }

        // Whether every saved word carries a sense — the one number that says the sense
        // migration did what it says. `withoutSense` settles at the words whose article is
        // not written yet; it should never climb.
        get("/saved-senses") {
            if (call.rejectedAsNonAdmin()) return@get
            val coverage = savedWords.senseCoverage()
            call.respond(
                ApiResponse.success(
                    mapOf(
                        "total" to coverage.total.toString(),
                        "withSense" to coverage.withSense.toString(),
                        "withoutSense" to coverage.withoutSense.joinToString(", ")
                    )
                )
            )
        }

        // Paged rather than whole: the corpus is the thing that grows without bound here.
        get("/entries") {
            if (call.rejectedAsNonAdmin()) return@get
            val search = call.request.queryParameters["search"]
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()
                ?.coerceIn(5, 100) ?: 25
            call.respond(ApiResponse.success(repository.browse(search, page, pageSize)))
        }
    }

    route("/api/admin/warmup/queue") {

        get {
            if (call.rejectedAsNonAdmin()) return@get
            val done = repository.allLemmas()
            val items = queue.all().map { (word, addedAt) ->
                WarmupQueueItem(word = word, done = word in done, addedAt = addedAt)
            }
            call.respond(
                ApiResponse.success(
                    WarmupQueueView(items = items, pending = items.count { !it.done })
                )
            )
        }

        /**
         * Accepts a free-form list — one per line, or comma separated, however it was pasted.
         * Rejected words are reported rather than dropped, so a typo in a pasted column does not
         * vanish into a queue that silently never warms it.
         */
        post {
            if (call.rejectedAsNonAdmin()) return@post
            val request = call.receive<AddQueueWordsRequest>()

            val candidates = request.words
                .split('\n', ',', ';', '\t')
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()

            val added = mutableListOf<String>()
            val alreadyQueued = mutableListOf<String>()
            val rejected = mutableListOf<String>()

            for (word in candidates) {
                // Letters, spaces and hyphens only: phrases are legitimate, punctuation is not.
                if (word.length > 64 || !word.all { it.isLetter() || it == ' ' || it == '-' }) {
                    rejected += word
                    continue
                }
                if (queue.add(word)) added += word else alreadyQueued += word
            }

            call.respond(
                ApiResponse.success(
                    AddQueueWordsResult(added = added, alreadyQueued = alreadyQueued, rejected = rejected)
                )
            )
        }

        delete("/{word}") {
            if (call.rejectedAsNonAdmin()) return@delete
            val word = call.parameters["word"].orEmpty()
            call.respond(ApiResponse.success(mapOf("removed" to queue.remove(word))))
        }
    }
}
