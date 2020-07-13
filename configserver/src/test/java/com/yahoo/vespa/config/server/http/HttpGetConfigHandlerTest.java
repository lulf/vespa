// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.TestComponentRegistry;
import com.yahoo.vespa.config.server.TestConfigDefinitionRepo;
import com.yahoo.vespa.config.server.application.OrchestratorMock;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.Collections;

import static com.yahoo.jdisc.http.HttpRequest.Method.GET;
import static com.yahoo.jdisc.http.HttpResponse.Status.BAD_REQUEST;
import static com.yahoo.jdisc.http.HttpResponse.Status.NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author Ulf Lilleengen
 * @author hmusum
 */
public class HttpGetConfigHandlerTest {

    private static final TenantName tenant = TenantName.from("default");
    private static final String expected =
            "{\"port\":{\"telnet\":19098,\"rpc\":19097},\"application\":{\"tenant\":\"default\",\"name\":\"default\"";
    private static final String baseUri = "http://yahoo.com:8080/config/v1/";
    private static final String configUri = baseUri + "cloud.config.sentinel/hosts/localhost/sentinel";
    private final static File testApp = new File("src/test/resources/deploy/validapp");
    private static final ApplicationId applicationId = ApplicationId.defaultId();

    private HttpGetConfigHandler handler;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws IOException {
        TestComponentRegistry componentRegistry = new TestComponentRegistry.Builder()
                .configDefinitionRepo(new TestConfigDefinitionRepo())
                .configServerConfig(new ConfigserverConfig.Builder()
                                            .configServerDBDir(temporaryFolder.newFolder().getAbsolutePath())
                                            .configDefinitionsDir(temporaryFolder.newFolder().getAbsolutePath())
                                            .build())
                .build();
        TenantRepository tenantRepository = new TenantRepository(componentRegistry);
        tenantRepository.addTenant(tenant);
        ApplicationRepository applicationRepository =
                new ApplicationRepository(tenantRepository,
                                          new SessionHandlerTest.MockProvisioner(),
                                          new OrchestratorMock(),
                                          Clock.systemUTC());
        handler = new HttpGetConfigHandler(HttpGetConfigHandler.testOnlyContext(), tenantRepository);
        applicationRepository.deploy(testApp, prepareParams());
    }

    @Test
    public void require_that_handler_can_be_created() throws IOException {
        HttpResponse response = handler.handle(HttpRequest.createTestRequest(configUri, GET));
        String renderedString = SessionHandlerTest.getRenderedString(response);
        assertTrue(renderedString, renderedString.startsWith(expected));
    }
    
    @Test
    public void require_correct_error_response() throws IOException {
        final String nonExistingConfigNameUri = baseUri + "nonexisting.config/myid";
        final String nonExistingConfigUri = baseUri + "cloud.config.sentinel/myid/nonexisting/id";
        final String illegalConfigNameUri = baseUri + "/foobar/myid";

        HttpResponse response = handler.handle(HttpRequest.createTestRequest(nonExistingConfigNameUri, GET));
        HandlerTest.assertHttpStatusCodeErrorCodeAndMessage(response, NOT_FOUND, HttpErrorResponse.errorCodes.NOT_FOUND, "No such config: nonexisting.config");
        assertTrue(SessionHandlerTest.getRenderedString(response).contains("No such config:"));
        response = handler.handle(HttpRequest.createTestRequest(nonExistingConfigUri, GET));
        HandlerTest.assertHttpStatusCodeErrorCodeAndMessage(response, NOT_FOUND, HttpErrorResponse.errorCodes.NOT_FOUND, "No such config id: myid/nonexisting/id");
        assertEquals(response.getContentType(), "application/json");
        assertTrue(SessionHandlerTest.getRenderedString(response).contains("No such config id:"));
        response = handler.handle(HttpRequest.createTestRequest(illegalConfigNameUri, GET));
        HandlerTest.assertHttpStatusCodeErrorCodeAndMessage(response, BAD_REQUEST, HttpErrorResponse.errorCodes.BAD_REQUEST, "Illegal config, must be of form namespace.name.");
    }

    @Test
    public void require_that_nocache_property_works() throws IOException {
        HttpRequest request = HttpRequest.createTestRequest(configUri, GET, null, Collections.singletonMap("nocache", "true"));
        HttpResponse response = handler.handle(request);
        String renderedString = SessionHandlerTest.getRenderedString(response);
        assertTrue(renderedString, renderedString.startsWith(expected));
    }

    private PrepareParams prepareParams() {
        return new PrepareParams.Builder().applicationId(applicationId).build();
    }

}
