package spp.cli.commands.developer.instrument

import com.apollographql.apollo.api.ScalarTypeAdapters
import com.apollographql.apollo.api.internal.SimpleResponseWriter
import com.apollographql.apollo.coroutines.await
import com.apollographql.apollo.exception.ApolloException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import instrument.AddLiveLogMutation
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import spp.cli.Main
import spp.cli.commands.PlatformCLI
import spp.cli.commands.PlatformCLI.apolloClient
import spp.cli.util.JsonCleaner.cleanJson
import type.InstrumentThrottleInput
import type.LiveLogInput
import type.LiveSourceLocationInput
import type.ThrottleStep
import kotlin.system.exitProcess

class AddLiveLog : CliktCommand() {

    val source by argument(help = "Qualified class name")
    val line by argument(help = "Line number").int()
    val logFormat by argument(help = "Log format")
    val logArguments by option("-logArgument", "-l", help = "Log argument").multiple()
    val condition by option("-condition", "-c", help = "Trigger condition")
    val expiresAt by option("-expiresAt", "-e", help = "Expiration time (epoch time [ms])").long()
    val hitLimit by option("-hitLimit", "-h", help = "Trigger hit limit").int()
    val throttleLimit by option("-throttleLimit", "-t", help = "Trigger throttle limit").int().default(1)
    val throttleStep by option("-throttleStep", "-s", help = "Trigger throttle step").enum<ThrottleStep>()
        .default(ThrottleStep.SECOND)

    override fun run() = runBlocking {
        val input = LiveLogInput.builder()
            .location(LiveSourceLocationInput.builder().source(source).line(line).build())
            .logFormat(logFormat)
            .logArguments(logArguments)
            .condition(condition)
            .expiresAt(expiresAt)
            .hitLimit(hitLimit)
            .throttle(InstrumentThrottleInput.builder().limit(throttleLimit).step(throttleStep).build())
            .build()
        val response = try {
            apolloClient.mutate(AddLiveLogMutation(input)).await()
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

        echo(response.data!!.addLiveLog().let {
            val respWriter = SimpleResponseWriter(ScalarTypeAdapters.DEFAULT)
            it.marshaller().marshal(respWriter)
            cleanJson(JsonObject(respWriter.toJson("")).getJsonObject("data")).encodePrettily()
        })
        if (Main.standalone) exitProcess(0)
    }
}
