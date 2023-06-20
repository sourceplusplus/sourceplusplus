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

import graphql.ExceptionWhileDataFetching
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class LoggerDataFetcherExceptionHandler : DataFetcherExceptionHandler {

    private val log = LoggerFactory.getLogger(LoggerDataFetcherExceptionHandler::class.java)

    override fun handleException(
        handlerParameters: DataFetcherExceptionHandlerParameters
    ): CompletableFuture<DataFetcherExceptionHandlerResult> {
        val exception = handlerParameters.exception
        exception.message?.let { log.warn(it) }
        val sourceLocation = handlerParameters.sourceLocation
        val path = handlerParameters.path
        val error = ExceptionWhileDataFetching(path, exception, sourceLocation)
        return CompletableFuture.completedFuture(
            DataFetcherExceptionHandlerResult.newResult().error(error).build()
        )
    }
}
