package ch.abwesend.foldervault.domain.coroutine

import ch.abwesend.foldervault.domain.util.Constants
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

suspend fun <T, S> Collection<T>.mapAsync(mapper: suspend (T) -> S): List<S> = coroutineScope {
    map { async { mapper(it) } }.awaitAll()
}

suspend fun <T, S> Collection<T>.mapAsyncChunked(
    chunkSize: Int = Constants.DEFAULT_CHUNK_SIZE,
    mapper: suspend (T) -> S,
): List<S> = coroutineScope {
    chunked(chunkSize).flatMap { chunk -> chunk.mapAsync(mapper) }
}
