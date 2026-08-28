package revxrsal.kitsune.codegen.statics

import kotlinx.serialization.Serializable

@Serializable
data class TauriConfigJson(
    val productName: String,
    val version: String,
    val identifier: String
)
