/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.platform.core.api.graphql

import graphql.ExecutionResult
import graphql.execution.instrumentation.ExecutionStrategyInstrumentationContext
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationValidationParameters
import graphql.validation.ValidationError
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class LoggerInstrumentation : SimpleInstrumentation() {

    private val log = LoggerFactory.getLogger(LoggerInstrumentation::class.java)

    override fun instrumentExecutionResult(
        executionResult: ExecutionResult?,
        parameters: InstrumentationExecutionParameters?,
        state: InstrumentationState?
    ): CompletableFuture<ExecutionResult> {
        if (executionResult?.errors?.isNotEmpty() == true) {
            executionResult.errors.forEach { log.warn("GraphQL execution failed: {}", it) }
        }
        return super.instrumentExecutionResult(executionResult, parameters, state)
    }

    override fun beginValidation(
        parameters: InstrumentationValidationParameters?,
        state: InstrumentationState?
    ): InstrumentationContext<MutableList<ValidationError>> {
        val theSuper = super.beginValidation(parameters, state)
        return object : InstrumentationContext<MutableList<ValidationError>> {
            override fun onDispatched(result: CompletableFuture<MutableList<ValidationError>>?) {
                theSuper?.onDispatched(result)
            }

            override fun onCompleted(result: MutableList<ValidationError>?, t: Throwable?) {
                theSuper?.onCompleted(result, t)
                if (t != null) log.warn("GraphQL validation failed", t)
                result?.let { if (it.isNotEmpty()) log.warn("GraphQL validation failed: {}", it) }
            }
        }
    }

    override fun beginExecutionStrategy(
        parameters: InstrumentationExecutionStrategyParameters?,
        state: InstrumentationState?
    ): ExecutionStrategyInstrumentationContext = object : ExecutionStrategyInstrumentationContext {
        override fun onDispatched(result: CompletableFuture<ExecutionResult>?) = Unit
        override fun onCompleted(result: ExecutionResult?, t: Throwable?) {
            if (t != null) log.warn("GraphQL execution failed", t)
        }
    }
}
