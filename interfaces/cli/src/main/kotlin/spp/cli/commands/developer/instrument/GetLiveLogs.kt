package spp.cli.commands.developer.instrument

import com.apollographql.apollo.api.ScalarTypeAdapters
import com.apollographql.apollo.api.internal.SimpleResponseWriter
import com.apollographql.apollo.coroutines.await
import com.apollographql.apollo.exception.ApolloException
import com.github.ajalt.clikt.core.CliktCommand
import instrument.GetLiveLogsQuery
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import spp.cli.Main
import spp.cli.commands.PlatformCLI
import spp.cli.commands.PlatformCLI.apolloClient
import spp.cli.util.JsonCleaner.cleanJson
import kotlin.system.exitProcess

class GetLiveLogs : CliktCommand() {

    override fun run() = runBlocking {
        val response = try {
            apolloClient.query(GetLiveLogsQuery()).await()
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

        echo(response.data!!.liveLogs.map {
            val respWriter = SimpleResponseWriter(ScalarTypeAdapters.DEFAULT)
            it.marshaller().marshal(respWriter)
            cleanJson(JsonObject(respWriter.toJson("")).getJsonObject("data")).encodePrettily()
        })
        if (Main.standalone) exitProcess(0)
    }
}
