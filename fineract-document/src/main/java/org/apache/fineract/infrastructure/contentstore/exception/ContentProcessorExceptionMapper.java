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
 * Maps {@link ContentProcessorException} to a 500.
 *
 * Kept at 500 on purpose. The exception is raised both for genuine processing failures (an IO error while piping
 * content) and for a caller-supplied image format the platform has no reader for, and the type carries nothing that
 * tells the two apart. Since an unmapped exception already returns 500 today, labelling it {@code
 * error.msg.content.processor} is a strict improvement, whereas guessing 400 would risk reporting a server fault as a
 * client error. Split the type first if these ever need different statuses.
 */
@Provider
@Component
@Scope("singleton")
@Slf4j
public class ContentProcessorExceptionMapper implements FineractExceptionMapper, ExceptionMapper<ContentProcessorException> {

    @Override
    public Response toResponse(final ContentProcessorException exception) {
        log.error("Content processing failure", ErrorHandler.findMostSpecificException(exception));
        return Response.status(HttpStatus.SC_INTERNAL_SERVER_ERROR)
                .entity(ApiGlobalErrorResponse.serverSideError(exception.getGlobalisationMessageCode(), exception.getDefaultUserMessage()))
                .type(MediaType.APPLICATION_JSON).build();
    }

    @Override
    public int errorCode() {
        return 5001;
    }
}
