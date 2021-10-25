package spp.cli

import com.github.ajalt.clikt.core.subcommands
import spp.cli.commands.PlatformCLI
import spp.cli.commands.admin.Admin
import spp.cli.commands.admin.access.*
import spp.cli.commands.admin.developer.AddDeveloper
import spp.cli.commands.admin.developer.GetDevelopers
import spp.cli.commands.admin.developer.RefreshDeveloperToken
import spp.cli.commands.admin.developer.RemoveDeveloper
import spp.cli.commands.admin.permission.AddRolePermission
import spp.cli.commands.admin.permission.GetDeveloperPermissions
import spp.cli.commands.admin.permission.GetRolePermissions
import spp.cli.commands.admin.permission.RemoveRolePermission
import spp.cli.commands.admin.role.*
import spp.cli.commands.developer.Developer
import spp.cli.commands.developer.instrument.*
import spp.cli.commands.system.System
import spp.cli.commands.system.etc.Reset
import spp.cli.commands.system.etc.Version

object Main {

    var standalone = true
    lateinit var args: Array<String>

    @JvmStatic
    fun main(args: Array<String>) {
        Main.args = args
        PlatformCLI.subcommands(
            System().subcommands(
                //etc
                Reset(),
                Version()
            ),
            Admin().subcommands(
                //role
                AddRole(),
                GetDeveloperRoles(),
                GetRoles(),
                RemoveRole(),
                AddDeveloperRole(),
                RemoveDeveloperRole(),
                //permission
                AddRolePermission(),
                GetDeveloperPermissions(),
                GetRolePermissions(),
                RemoveRolePermission(),
                //developer
                AddDeveloper(),
                GetDevelopers(),
                RemoveDeveloper(),
                RefreshDeveloperToken(),
                //instrument access
                AddAccessPermission(),
                AddRoleAccessPermission(),
                GetAccessPermissions(),
                GetDeveloperAccessPermissions(),
                GetRoleAccessPermissions(),
                RemoveAccessPermission(),
                RemoveRoleAccessPermission()
            ),
            Developer().subcommands(
                //instrument
                AddLiveBreakpoint(),
                AddLiveLog(),
                GetLiveBreakpoints(),
                GetLiveInstruments(),
                GetLiveLogs(),
                RemoveLiveInstrument(),
                RemoveLiveInstruments(),
                ClearLiveInstruments()
            )
        ).main(args)
    }
}
