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

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Provider
@Component
@Scope("singleton")
@Slf4j
public class DefaultExceptionMapper implements FineractExceptionMapper, ExceptionMapper<RuntimeException> {

    @Override
    public int errorCode() {
        return 9999;
    }

    @Override
    public Response toResponse(RuntimeException exception) {
        // WebApplicationException is a RuntimeException, so once this mapper is registered Jersey routes the
        // framework's own 404/405/406 here and would turn them into 500s. Jersey only short-circuits a
        // WebApplicationException before consulting a mapper when its response carries an entity
        // (ServerRuntime.mapException), and the framework-generated ones do not. Handing back the exception's own
        // response reproduces the fallback Jersey applies when no mapper matches, which is today's behaviour.
        if (exception instanceof WebApplicationException wae && wae.getResponse() != null) {
            return wae.getResponse();
        }

        log.warn("Exception occurred", ErrorHandler.findMostSpecificException(exception));

        return Response.status(SC_INTERNAL_SERVER_ERROR)
                .entity(Map.of("Exception", Objects.requireNonNullElse(exception.getMessage(), "No error message available")))
                .type(MediaType.APPLICATION_JSON).build();
    }
}
