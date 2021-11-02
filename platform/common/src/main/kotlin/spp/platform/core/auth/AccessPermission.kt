package spp.platform.core.auth

data class AccessPermission(
    val id: String,
    val locationPatterns: List<String>,
    val type: AccessType
)
