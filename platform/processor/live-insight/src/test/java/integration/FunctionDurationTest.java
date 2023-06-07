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
package integration;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import spp.protocol.artifact.ArtifactQualifiedName;
import spp.protocol.artifact.ArtifactType;
import spp.protocol.insight.InsightType;

import java.io.File;

public class FunctionDurationTest extends PlatformIntegrationTest {

    private void function1() throws Exception {
        function2();
        function3();
    }

    private void function2() throws Exception {
        Thread.sleep(100);
    }

    private void function3() throws Exception {
        Thread.sleep(200);
    }

    @Test
    public void testFunctionDuration() throws Exception {
        var testContext = new VertxTestContext();

        //upload source code
        var sourceFile = new File("src/test/java/integration/FunctionDurationTest.java");
        getInsightService().uploadSourceCode(new JsonObject()
                .put("file_path", sourceFile.getAbsolutePath())
                .put("file_content", vertx.fileSystem().readFile(
                        sourceFile.getAbsolutePath()).toCompletionStage().toCompletableFuture().get()
                )
        ).toCompletionStage().toCompletableFuture().get();

        //keep executing function1
        vertx.setPeriodic(1000, id -> {
            try {
                function1();
            } catch (Exception e) {
                testContext.failNow(e);
            }

            if (testContext.completed() || testContext.failed()) {
                vertx.cancelTimer(id);
            }
        });

        //keep requesting function duration insight for function1
        vertx.setPeriodic(1000, id -> {
            getInsightService().getArtifactInsights(
                    new ArtifactQualifiedName(
                            FunctionDurationTest.class.getName() + ".function1()",
                            null,
                            ArtifactType.FUNCTION,
                            null,
                            null
                    ),
                    JsonArray.of(InsightType.FUNCTION_DURATION.name())
            ).toCompletionStage().toCompletableFuture().thenAccept(insights -> {
                var durationInsight = insights.getJsonArray(InsightType.FUNCTION_DURATION.name());
                if (durationInsight != null) {
                    var functionDuration = durationInsight.getJsonObject(0);
                    var duration = functionDuration.getInteger(FunctionDurationTest.class.getName() + ".function1()");
                    if (Math.abs(duration - 300) <= 20) {
                        testContext.completeNow();
                    }
                }
            }).exceptionally(e -> {
                testContext.failNow(e);
                return null;
            });

            if (testContext.completed() || testContext.failed()) {
                vertx.cancelTimer(id);
            }
        });

        errorOnTimeout(testContext, 30);
    }
}
