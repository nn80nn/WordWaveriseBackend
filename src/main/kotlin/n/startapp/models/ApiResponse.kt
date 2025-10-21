package n.startapp.models

import kotlinx.serialization.Serializable

/**
 * Generic wrapper for API responses
 * @param T The type of data being returned
 * @property status Response status ("ok" or "error")
 * @property data The actual response data (nullable)
 * @property message Optional message (used for errors or additional info)
 */
@Serializable
data class ApiResponse<T>(
    val status: String,
    val data: T? = null,
    val message: String? = null
) {
    companion object {
        /**
         * Creates a successful API response
         */
        fun <T> success(data: T, message: String? = null): ApiResponse<T> {
            return ApiResponse(
                status = "ok",
                data = data,
                message = message
            )
        }

        /**
         * Creates an error API response
         */
        fun <T> error(message: String, data: T? = null): ApiResponse<T> {
            return ApiResponse(
                status = "error",
                data = data,
                message = message
            )
        }
    }
}
