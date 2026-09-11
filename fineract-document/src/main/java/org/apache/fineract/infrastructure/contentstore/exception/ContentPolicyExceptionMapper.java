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
import org.apache.fineract.infrastructure.core.exceptionmapper.FineractExceptionMapper;
import org.apache.http.HttpStatus;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Maps {@link ContentPolicyException} to a 400.
 *
 * A content policy violation is a client error: the file name did not match {@code fineract.content.regex-whitelist},
 * the declared mime type was not on {@code fineract.content.mime-whitelist}, or the uploaded bytes did not match the
 * declared mime type. Without this mapper the exception reaches Jersey unmapped and the caller gets a bare 500 that is
 * indistinguishable from a broken content store — see also {@link ContentStoreExceptionMapper}.
 */
@Provider
@Component
@Scope("singleton")
@Slf4j
public class ContentPolicyExceptionMapper implements FineractExceptionMapper, ExceptionMapper<ContentPolicyException> {

    @Override
    public Response toResponse(final ContentPolicyException exception) {
        log.warn("Content policy violation: {}", exception.getDefaultUserMessage());
        return Response.status(HttpStatus.SC_BAD_REQUEST)
                .entity(ApiGlobalErrorResponse.badClientRequest(exception.getGlobalisationMessageCode(), exception.getDefaultUserMessage()))
                .type(MediaType.APPLICATION_JSON).build();
    }

    @Override
    public int errorCode() {
        return 4000;
    }
}
