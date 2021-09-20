package spp.cli.commands.admin.access

import access.RemoveAccessPermissionMutation
import com.apollographql.apollo.coroutines.await
import com.apollographql.apollo.exception.ApolloException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.coroutines.runBlocking
import spp.cli.Main
import spp.cli.commands.PlatformCLI
import kotlin.system.exitProcess

class RemoveAccessPermission : CliktCommand(printHelpOnEmptyArgs = true) {

    val id by argument(help = "Access permission id")

    override fun run() = runBlocking {
        val response = try {
            PlatformCLI.apolloClient.mutate(
                RemoveAccessPermissionMutation(id)
            ).await()
        } catch (e: ApolloException) {
            echo(e.message, err = true)
            if (PlatformCLI.verbose) {
                echo(e.stackTraceToString(), err = true)
            }
            if (Main.standalone) exitProcess(-1) else return@runBlocking
        }
        if (response.hasErrors()) {
            echo(response.errors?.get(0)?.message, err = true)
            if (Main.standalone) exitProcess(-1) else return@runBlocking
        }

        echo(response.data!!.removeAccessPermission())
        if (Main.standalone) exitProcess(0)
    }
}
