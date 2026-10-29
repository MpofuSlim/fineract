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
package org.apache.fineract.infrastructure.security.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.data.AuthenticatedUserData;
import org.apache.fineract.infrastructure.security.service.SpringSecurityPlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * POST /v1/authentication must authenticate through the AuthenticationManager (which publishes the success and failure
 * events the login-retry lockout counts), not through the authentication provider directly. That the real manager
 * delivers a wrong password here to the lockout is pinned by SecurityConfigTest in fineract-provider, which owns the
 * manager.
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationApiResourceTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private ToApiJsonSerializer<AuthenticatedUserData> serializer;
    @Mock
    private SpringSecurityPlatformSecurityContext platformSecurityContext;

    private AuthenticationApiResource resource;

    @BeforeEach
    void setUp() {
        resource = new AuthenticationApiResource(authenticationManager, serializer, platformSecurityContext);
    }

    @Test
    void badCredentialsAreRejectedByTheAuthenticationManager() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        assertThrows(BadCredentialsException.class, () -> resource.authenticate("{\"username\":\"mifos\",\"password\":\"wrong\"}"));

        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(captor.capture());
        assertEquals("mifos", captor.getValue().getName());
        assertEquals("wrong", captor.getValue().getCredentials());
    }

    @Test
    void successfulLoginIsAuthenticatedByTheAuthenticationManager() {
        AppUser principal = mock(AppUser.class);
        Office office = mock(Office.class);
        when(principal.getRoles()).thenReturn(Set.of());
        when(principal.getOffice()).thenReturn(office);
        when(principal.getId()).thenReturn(1L);
        when(authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(principal, "password", List.of()));
        when(serializer.serialize(any(AuthenticatedUserData.class))).thenReturn("{}");

        assertEquals("{}", resource.authenticate("{\"username\":\"mifos\",\"password\":\"password\"}"));

        ArgumentCaptor<AuthenticatedUserData> captor = ArgumentCaptor.forClass(AuthenticatedUserData.class);
        verify(serializer).serialize(captor.capture());
        assertTrue(captor.getValue().isAuthenticated());
        assertEquals(1L, captor.getValue().getUserId());
    }
}
