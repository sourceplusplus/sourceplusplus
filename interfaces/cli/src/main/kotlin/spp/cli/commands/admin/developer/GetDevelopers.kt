package spp.cli.commands.admin.developer

import com.apollographql.apollo.coroutines.await
import com.apollographql.apollo.exception.ApolloException
import com.github.ajalt.clikt.core.CliktCommand
import developer.GetDevelopersQuery
import kotlinx.coroutines.runBlocking
import spp.cli.Main
import spp.cli.commands.PlatformCLI
import spp.cli.commands.PlatformCLI.apolloClient
import kotlin.system.exitProcess

class GetDevelopers : CliktCommand() {

    override fun run() = runBlocking {
        val response = try {
            apolloClient.query(GetDevelopersQuery()).await()
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

        echo(response.data!!.developers.map { it.id() })
        if (Main.standalone) exitProcess(0)
    }
}
