package spp.platform.core.auth.error

import spp.platform.core.auth.RolePermission

class PermissionAccessDenied : RuntimeException {

    private val permission: RolePermission

    constructor(permission: RolePermission) : this(permission, "Permission access denied: ${permission.name}")

    private constructor(permission: RolePermission, message: String) : super(message) {
        this.permission = permission
    }

    fun toEventBusException(): PermissionAccessDenied {
        return PermissionAccessDenied(permission, "EventBusException:PermissionAccessDenied[$permission]")
    }
}
