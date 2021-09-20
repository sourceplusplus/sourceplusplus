package spp.platform.core.auth

import org.apache.commons.lang3.EnumUtils

enum class DeveloperRole(var roleName: String, var nativeRole: Boolean) {
    ROLE_MANAGER("role_manager", true),
    ROLE_USER("role_user", true),
    USER("*", false);

    companion object {
        fun fromString(roleName: String): DeveloperRole {
            val nativeRole = EnumUtils.getEnum(DeveloperRole::class.java, roleName.toUpperCase())
            return if (nativeRole != null) {
                nativeRole
            } else {
                val user = USER
                user.roleName = roleName.toLowerCase().replace(' ', '_').trim()
                user
            }
        }
    }
}
