package io.neonbee.endpoint.raw;

import static com.google.common.truth.Truth.assertThat;
import static io.neonbee.endpoint.raw.RawEndpoint.DEFAULT_BASE_PATH;
import static io.neonbee.endpoint.raw.RawEndpoint.RawHandler.determineQualifiedName;
import static io.neonbee.test.helper.DeploymentHelper.NEONBEE_NAMESPACE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.neonbee.data.DataAdapter;
import io.neonbee.data.DataContext;
import io.neonbee.data.DataException;
import io.neonbee.data.DataQuery;
import io.neonbee.data.DataVerticle;
import io.neonbee.test.base.DataVerticleTestBase;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxTestContext;

class RawEndpointTest extends DataVerticleTestBase {
    private static RoutingContext mockRoutingContext(String routingPath) {
        RoutingContext routingContextMock = mock(RoutingContext.class);
        Route routeMock = mock(Route.class);

        when(routingContextMock.normalizedPath()).thenReturn(DEFAULT_BASE_PATH + routingPath);
        when(routingContextMock.mountPoint()).thenReturn(DEFAULT_BASE_PATH);
        when(routingContextMock.currentRoute()).thenReturn(routeMock);
        when(routeMock.getPath()).thenReturn(null);

        return routingContextMock;
    }

    @Test
    @DisplayName("check qualified name")
    void checkQualifiedName() {
        assertThat(determineQualifiedName(mockRoutingContext("Verticle"))).isEqualTo("Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("_verticle"))).isEqualTo("_verticle");
        assertThat(determineQualifiedName(mockRoutingContext("namespace/Verticle"))).isEqualTo("namespace/Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("namespace/_Verticle"))).isEqualTo("namespace/_Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("nameSPACE/Verticle"))).isEqualTo("namespace/Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("nsA/nsB/Verticle"))).isEqualTo("nsa/nsb/Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("nsA/nsB/_Verticle"))).isEqualTo("nsa/nsb/_Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("Verticle/Path/MorePath"))).isEqualTo("Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("nsA/nsB/Verticle/Path"))).isEqualTo("nsa/nsb/Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("nsA/nsB/_Verticle/Path"))).isEqualTo("nsa/nsb/_Verticle");
        assertThat(determineQualifiedName(mockRoutingContext("neonbee/TestVerticle-23-42/hodor/hodor")))
                .isEqualTo("neonbee/TestVerticle-23-42");

        assertThat(determineQualifiedName(mockRoutingContext("/raw/x"))).isNull();
        assertThat(determineQualifiedName(mockRoutingContext("verticle"))).isNull();
        assertThat(determineQualifiedName(mockRoutingContext("nameSPACE/verticle"))).isNull();
        assertThat(determineQualifiedName(mockRoutingContext("nsA/nsB/verticle/path"))).isNull();
    }

    static Stream<Arguments> customStatusCodes() {
        return Stream.of(Arguments.of(BAD_REQUEST.code()), Arguments.of(FORBIDDEN.code()),
                Arguments.of(NOT_FOUND.code()), Arguments.of(INTERNAL_SERVER_ERROR.code()));
    }

    @ParameterizedTest(name = "{index}: with status code {0}")
    @MethodSource("customStatusCodes")
    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    @DisplayName("RawDataEndpointHandler must forward custom status codes from DataExceptions to the client")
    void testHTTPExceptions(int statusCode, VertxTestContext testContext) {
        String verticleName = "TestVerticle" + UUID.randomUUID().toString();
        Verticle dummyVerticle = createDummyDataVerticle(NEONBEE_NAMESPACE + '/' + verticleName)
                .withDataAdapter(new DataAdapter<String>() {

                    @Override
                    public Future<String> retrieveData(DataQuery query, DataContext context) {
                        return Future.failedFuture(new DataException(statusCode));
                    }
                });

        deployVerticle(dummyVerticle).compose(v -> sendRequest(verticleName, "", ""))
                .onComplete(testContext.succeeding(resp -> {
                    testContext.verify(() -> assertThat(resp.statusCode()).isEqualTo(statusCode));
                    testContext.completeNow();
                }));
    }

    @Test
    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    @DisplayName("RawDataEndpoint must prohibit requests to DataVerticles starting with underscore by default")
    void testProhibitRequestsToUnderScoreDVsByDefault(VertxTestContext testContext) {
        String verticleName = "_TestVerticle" + UUID.randomUUID().toString();

        DataVerticle<String> dummy =
                createDummyDataVerticle(NEONBEE_NAMESPACE + '/' + verticleName).withStaticResponse("You have failed");
        deployVerticle(dummy).compose(s -> sendRequest(verticleName, "", ""))
                .onComplete(testContext.succeeding(res -> testContext.verify(() -> {
                    assertThat(res.statusCode()).isEqualTo(404);
                    testContext.completeNow();
                })));
    }

    @Test
    @Timeout(value = 2, timeUnit = TimeUnit.SECONDS)
    @DisplayName("RawDataEndpointHandler must set uriPath and query correct")
    void testPassQueryToDataQuery(VertxTestContext testContext) {
        String verticleName = "TestVerticle" + UUID.randomUUID().toString();
        String expectedQuery = "Hodor=Hodor&Foo=123";
        String expectedUriPath = "/hodor/hodor";

        DataVerticle<String> dummy = createDummyDataVerticle(NEONBEE_NAMESPACE + '/' + verticleName)
                .withDynamicResponse((query, context) -> {
                    testContext.verify(() -> {
                        assertThat(query.getRawQuery()).contains("Hodor=Hodor");
                        assertThat(query.getRawQuery()).contains("Foo=123");
                        assertThat(query.getUriPath()).isEqualTo(expectedUriPath);
                    });
                    testContext.completeNow();
                    return "";
                });

        deployVerticle(dummy).compose(s -> sendRequest(verticleName, expectedUriPath, expectedQuery))
                .onComplete(testContext.succeedingThenComplete());
    }

    private Future<HttpResponse<Buffer>> sendRequest(String verticleName, String path, String query) {
        String uriPath = String.format("/raw/%s/%s/%s?%s", NEONBEE_NAMESPACE, verticleName, path, query);
        return createRequest(HttpMethod.GET, uriPath).send();
    }
}
