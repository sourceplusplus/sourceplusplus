package spp.cli.commands.admin.access

import access.AddAccessPermissionMutation
import com.apollographql.apollo.api.Input
import com.apollographql.apollo.api.ScalarTypeAdapters
import com.apollographql.apollo.api.internal.SimpleResponseWriter
import com.apollographql.apollo.coroutines.await
import com.apollographql.apollo.exception.ApolloException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import spp.cli.Main
import spp.cli.commands.PlatformCLI
import spp.cli.util.JsonCleaner.cleanJson
import type.AccessType
import kotlin.system.exitProcess

class AddAccessPermission : CliktCommand(printHelpOnEmptyArgs = true) {

    val locationPatterns by option("-locationPattern", "-l", help = "Location pattern").multiple(required = true)
    val type by argument(help = "Access permission type").enum<AccessType>()

    override fun run() = runBlocking {
        val response = try {
            PlatformCLI.apolloClient.mutate(
                AddAccessPermissionMutation(Input.fromNullable(locationPatterns), type)
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

        echo(response.data!!.addAccessPermission().let {
            val respWriter = SimpleResponseWriter(ScalarTypeAdapters.DEFAULT)
            it.marshaller().marshal(respWriter)
            cleanJson(JsonObject(respWriter.toJson("")).getJsonObject("data")).encodePrettily()
        })
        if (Main.standalone) exitProcess(0)
    }
}
