/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.core.exceptionmapper;

import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;

import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DefaultExceptionMapperTest {

    @Test
    public void testDefaultExceptionMapper() {
        DefaultExceptionMapper defaultExceptionMapper = new DefaultExceptionMapper();
        Response response = defaultExceptionMapper.toResponse(new RuntimeException("error happened"));
        Assertions.assertEquals(9999, defaultExceptionMapper.errorCode());

        Assertions.assertEquals(Map.of("Exception", "error happened"), response.getEntity());
        Assertions.assertEquals(SC_INTERNAL_SERVER_ERROR, response.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
    }

    /**
     * The regression this mapper's registration introduces if left unguarded. {@code JerseyConfig.setup()} registers
     * only beans annotated {@code @Path} or {@code @Provider}, so before that annotation was added this mapper was
     * never consulted and unmapped errors reached the container. Registering it makes it the match for every
     * {@code RuntimeException} — {@code WebApplicationException} included, since Jersey walks the superclass chain to
     * find a mapper and only short-circuits a {@code WebApplicationException} beforehand when its response carries an
     * entity, which the framework-generated 404/405/406 do not. Without the passthrough these would all become 500s.
     */
    @ParameterizedTest
    @MethodSource("frameworkWebApplicationExceptions")
    void frameworkStatusesArePassedThroughUnchanged(WebApplicationException exception, int expectedStatus) {
        Response response = new DefaultExceptionMapper().toResponse(exception);

        Assertions.assertEquals(expectedStatus, response.getStatus());
    }

    static Object[][] frameworkWebApplicationExceptions() {
        return new Object[][] { { new NotFoundException(), 404 }, { new NotAllowedException("GET"), 405 },
                { new NotAcceptableException(), 406 } };
    }

    @Test
    void methodNotAllowedKeepsItsAllowHeader() {
        Response response = new DefaultExceptionMapper().toResponse(new NotAllowedException("GET", new String[] { "POST" }));

        Assertions.assertEquals(405, response.getStatus());
        // The Allow header is mandatory on a 405 and is carried on the exception's own response, so returning that
        // response rather than building a fresh one is what keeps the reply spec-compliant.
        Assertions.assertTrue(response.getAllowedMethods().containsAll(java.util.Set.of("GET", "POST")),
                "405 must keep its Allow header, was " + response.getAllowedMethods());
    }

    @Test
    void webApplicationExceptionCarryingAnEntityKeepsIt() {
        Response deliberate = Response.status(422).entity("{\"code\":\"deliberate\"}").type(MediaType.APPLICATION_JSON).build();

        Response response = new DefaultExceptionMapper().toResponse(new WebApplicationException(deliberate));

        Assertions.assertEquals(422, response.getStatus());
        Assertions.assertEquals("{\"code\":\"deliberate\"}", response.getEntity());
    }

    /**
     * The stock constructors substitute a 500 when handed a null response, but a subclass is free to return null from
     * {@code getResponse()}. There is nothing to pass through in that case, so the mapper must fall back to its own
     * JSON body rather than hand Jersey a null {@code Response}.
     */
    @Test
    void webApplicationExceptionWithoutAResponseFallsBackToTheJsonBody() {
        WebApplicationException noResponse = new WebApplicationException("no response") {

            @Override
            public Response getResponse() {
                return null;
            }
        };

        Response response = new DefaultExceptionMapper().toResponse(noResponse);

        Assertions.assertEquals(SC_INTERNAL_SERVER_ERROR, response.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
    }

    /**
     * Without {@code @Provider} this mapper is a plain Spring bean that Jersey never registers, which is why unmapped
     * runtime exceptions used to fall through to the container instead of this JSON body.
     */
    @Test
    void mapperIsAnnotatedProviderSoJerseyRegistersIt() {
        Assertions.assertTrue(DefaultExceptionMapper.class.isAnnotationPresent(Provider.class),
                "DefaultExceptionMapper must be annotated @Provider or JerseyConfig will not register it");
    }
}
