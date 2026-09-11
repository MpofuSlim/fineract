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
package org.apache.fineract.infrastructure.contentstore.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exceptionmapper.FineractExceptionMapper;
import org.apache.http.HttpStatus;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Maps {@link ContentStoreException} to a 500.
 *
 * The status is deliberately unchanged — an unwritable content volume or an unreachable object store genuinely is a
 * server fault. What changes is that the 500 now carries {@code error.msg.content.store} and the underlying message
 * instead of arriving anonymously, so a storage failure can be told apart from a {@link ContentPolicyException} without
 * reading the container log. Logged at ERROR because, unlike a policy violation, this always needs an operator.
 */
@Provider
@Component
@Scope("singleton")
@Slf4j
public class ContentStoreExceptionMapper implements FineractExceptionMapper, ExceptionMapper<ContentStoreException> {

    @Override
    public Response toResponse(final ContentStoreException exception) {
        log.error("Content store failure", ErrorHandler.findMostSpecificException(exception));
        return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR)
                .entity(ApiGlobalErrorResponse.serverSideError(exception.getGlobalisationMessageCode(), exception.getDefaultUserMessage()))
                .type(MediaType.APPLICATION_JSON).build();
    }

    @Override
    public int errorCode() {
        return 5000;
    }
}
