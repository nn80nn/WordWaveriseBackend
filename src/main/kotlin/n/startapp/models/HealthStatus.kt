package n.startapp.models

import kotlinx.serialization.Serializable

@Serializable
data class HealthStatus(
    val status: String,
    val version: String
)
