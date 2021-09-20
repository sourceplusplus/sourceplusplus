package spp.platform.core.auth

enum class RolePermission(val manager: Boolean = true) {
    RESET(true),
//    CLEAR_LIVE_INSTRUMENTS(true),

    //devs
    ADD_DEVELOPER(true),
    REMOVE_DEVELOPER(true),
    GET_DEVELOPERS(true),
    REFRESH_DEVELOPER_TOKEN(true),

    //roles
    ADD_ROLE(true),
    REMOVE_ROLE(true),
    GET_ROLES(true),
    GET_DEVELOPER_ROLES(true),
    ADD_DEVELOPER_ROLE(true),
    REMOVE_DEVELOPER_ROLE(true),

    //permissions
    GET_DEVELOPER_PERMISSIONS(true),
    GET_ROLE_PERMISSIONS(true),
    ADD_ROLE_PERMISSION(true),
    REMOVE_ROLE_PERMISSION(true),

    //instrument access
    GET_ACCESS_PERMISSIONS(true),
    GET_DATA_REDACTIONS(true),
    ADD_DATA_REDACTION(true),
    REMOVE_DATA_REDACTION(true),
    ADD_ACCESS_PERMISSION(true),
    REMOVE_ACCESS_PERMISSION(true),

    //instruments
    ADD_LIVE_LOG(false),
    ADD_LIVE_BREAKPOINT(false),
    GET_LIVE_INSTRUMENTS(false),
    GET_LIVE_LOGS(false),
    GET_LIVE_BREAKPOINTS(false),
    REMOVE_LIVE_INSTRUMENT(false)
}
