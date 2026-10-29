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

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.springframework.context.annotation.Scope;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Component;

/**
 * Answers a locked account exactly as {@link BadCredentialsExceptionMapper} answers a wrong password: 401 with the same
 * body.
 * <p>
 * Spring Security checks the lock before the password, so POST /v1/authentication for a locked account throws
 * {@link LockedException} whatever password is sent. Unmapped, it fell to {@link DefaultExceptionMapper}: a 500
 * carrying "User account is locked", which the console shows as a server error, and whose difference from a wrong
 * password's 401 tells an unauthenticated caller which usernames exist and are locked - an account it can lock itself
 * with enough wrong guesses once the login-retry lockout is enabled. Basic-auth API requests already answer a locked
 * account with the entry point's 401. The lock is still logged here, and an administrator unlocks the account.
 */
@Provider
@Component
@Scope("singleton")
@Slf4j
public class LockedExceptionMapper implements ExceptionMapper<LockedException> {

    @Override
    public Response toResponse(final LockedException exception) {
        log.warn("Exception occurred", ErrorHandler.findMostSpecificException(exception));
        return Response.status(Status.UNAUTHORIZED).entity(ApiGlobalErrorResponse.unAuthenticated()).type(MediaType.APPLICATION_JSON)
                .build();
    }
}
