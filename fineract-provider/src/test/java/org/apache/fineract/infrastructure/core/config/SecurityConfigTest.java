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
package org.apache.fineract.infrastructure.core.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.cache.service.CacheWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.domain.FineractRequestContextHolder;
import org.apache.fineract.infrastructure.core.filters.IdempotencyStoreHelper;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.MDCWrapper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.jobs.filter.ProgressiveLoanModelCheckerFilter;
import org.apache.fineract.infrastructure.security.api.AuthenticationApiResource;
import org.apache.fineract.infrastructure.security.data.AuthenticatedUserData;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.apache.fineract.infrastructure.security.service.LoginAttemptEventListener;
import org.apache.fineract.infrastructure.security.service.PlatformUserDetailsChecker;
import org.apache.fineract.infrastructure.security.service.SpringSecurityPlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.TenantAwareJpaPlatformUserDetailsService;
import org.apache.fineract.notification.service.UserNotificationService;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the request authorization rules and the authentication manager exactly as {@link SecurityConfig} builds
 * them, without a Spring context.
 */
class SecurityConfigTest {

    private SecurityConfig securityConfig;
    private ApplicationContext applicationContext;
    private TenantAwareJpaPlatformUserDetailsService userDetailsService;
    private ConfigurationDomainService lockoutConfiguration;
    private AppUserRepository appUserRepository;
    private LoginAttemptEventListener loginAttemptEventListener;
    private AuthorizationManager<HttpServletRequest> authorizationManager;

    @BeforeEach
    void setUp() throws Exception {
        securityConfig = new SecurityConfig();
        applicationContext = mock(ApplicationContext.class);
        userDetailsService = mock(TenantAwareJpaPlatformUserDetailsService.class);

        ReflectionTestUtils.setField(securityConfig, "applicationContext", applicationContext);
        ReflectionTestUtils.setField(securityConfig, "userDetailsService", userDetailsService);
        // deep stubs: two-factor, CORS, HSTS, IP tracking and SSL all read as disabled
        ReflectionTestUtils.setField(securityConfig, "fineractProperties", mock(FineractProperties.class, RETURNS_DEEP_STUBS));
        ReflectionTestUtils.setField(securityConfig, "serverProperties", mock(ServerProperties.class, RETURNS_DEEP_STUBS));
        ReflectionTestUtils.setField(securityConfig, "toApiJsonSerializer", mock(ToApiJsonSerializer.class));
        ReflectionTestUtils.setField(securityConfig, "configurationDomainService", mock(ConfigurationDomainService.class));
        ReflectionTestUtils.setField(securityConfig, "cacheWritePlatformService", mock(CacheWritePlatformService.class));
        ReflectionTestUtils.setField(securityConfig, "userNotificationService", mock(UserNotificationService.class));
        ReflectionTestUtils.setField(securityConfig, "basicAuthTenantDetailsService", mock(AuthTenantDetailsService.class));
        ReflectionTestUtils.setField(securityConfig, "businessDateReadPlatformService", mock(BusinessDateReadPlatformService.class));
        ReflectionTestUtils.setField(securityConfig, "mdcWrapper", mock(MDCWrapper.class));
        ReflectionTestUtils.setField(securityConfig, "fineractRequestContextHolder", mock(FineractRequestContextHolder.class));
        ReflectionTestUtils.setField(securityConfig, "idempotencyStoreHelper", mock(IdempotencyStoreHelper.class));
        ReflectionTestUtils.setField(securityConfig, "progressiveLoanModelCheckerFilter", mock(ProgressiveLoanModelCheckerFilter.class));
        ReflectionTestUtils.setField(securityConfig, "platformUserDetailsChecker", new PlatformUserDetailsChecker());
        lockoutConfiguration = mock(ConfigurationDomainService.class);
        appUserRepository = mock(AppUserRepository.class);
        loginAttemptEventListener = spy(new LoginAttemptEventListener(lockoutConfiguration, appUserRepository, mock(CacheManager.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class))));
        ReflectionTestUtils.setField(securityConfig, "loginAttemptEventListener", loginAttemptEventListener);

        authorizationManager = buildAuthorizationManager();
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void documentDetailAndAttachmentRequireReadDocument() {
        for (String path : new String[] { "/api/v1/clients/1/documents/5", "/api/v1/clients/1/documents/5/attachment",
                "/api/v1/loans/7/documents/5/attachment", "/api/v1/savings/3/documents/5", "/api/v1/staff/2/documents/5/attachment",
                "/api/v1/client_identifiers/4/documents/5/attachment", "/api/v1/groups/6/documents/5/attachment" }) {
            assertFalse(granted(HttpMethod.GET, path, "READ_CLIENT", "READ_LOAN"), path);
            assertTrue(granted(HttpMethod.GET, path, "READ_DOCUMENT"), path);
            assertTrue(granted(HttpMethod.GET, path, "ALL_FUNCTIONS_READ"), path);
            assertTrue(granted(HttpMethod.GET, path, "ALL_FUNCTIONS"), path);
        }
    }

    @Test
    void documentCollectionStillRequiresReadDocument() {
        assertFalse(granted(HttpMethod.GET, "/api/v1/clients/1/documents", "READ_CLIENT"));
        assertFalse(granted(HttpMethod.GET, "/api/v1/clients/1/documents/", "READ_CLIENT"));
        assertTrue(granted(HttpMethod.GET, "/api/v1/clients/1/documents", "READ_DOCUMENT"));
    }

    @Test
    void documentsOfEntityTypesThatWereNeverListedAreCovered() {
        // DocumentApiResource accepts any {entityType}; only seven were listed before
        assertFalse(granted(HttpMethod.GET, "/api/v1/centers/9/documents/5/attachment", "READ_CENTER"));
        assertTrue(granted(HttpMethod.GET, "/api/v1/centers/9/documents/5/attachment", "READ_DOCUMENT"));
        assertFalse(granted(HttpMethod.POST, "/api/v1/centers/9/documents", "READ_DOCUMENT"));
    }

    @Test
    void documentWritesRequireTheMatchingWritePermission() {
        assertFalse(granted(HttpMethod.POST, "/api/v1/clients/1/documents", "READ_DOCUMENT"));
        assertTrue(granted(HttpMethod.POST, "/api/v1/clients/1/documents", "CREATE_DOCUMENT"));

        // PUT and DELETE are only ever served on /{documentId}, which the exact-depth matchers never covered
        assertFalse(granted(HttpMethod.PUT, "/api/v1/clients/1/documents/5", "CREATE_DOCUMENT"));
        assertTrue(granted(HttpMethod.PUT, "/api/v1/clients/1/documents/5", "UPDATE_DOCUMENT"));
        assertFalse(granted(HttpMethod.DELETE, "/api/v1/loans/1/documents/5", "UPDATE_DOCUMENT"));
        assertTrue(granted(HttpMethod.DELETE, "/api/v1/loans/1/documents/5", "DELETE_DOCUMENT"));

        assertFalse(granted(HttpMethod.DELETE, "/api/v1/loans/1/documents/5", "ALL_FUNCTIONS_READ"));
        assertTrue(granted(HttpMethod.DELETE, "/api/v1/loans/1/documents/5", "ALL_FUNCTIONS_WRITE"));
    }

    @Test
    void imagesRequireReadDocumentWithOrWithoutTrailingSlash() {
        assertFalse(granted(HttpMethod.GET, "/api/v1/clients/1/images", "READ_CLIENT"));
        assertFalse(granted(HttpMethod.GET, "/api/v1/clients/1/images/", "READ_CLIENT"));
        assertFalse(granted(HttpMethod.GET, "/api/v1/staff/1/images/", "READ_STAFF"));
        assertTrue(granted(HttpMethod.GET, "/api/v1/clients/1/images/", "READ_DOCUMENT"));
        assertFalse(granted(HttpMethod.DELETE, "/api/v1/clients/1/images/", "READ_DOCUMENT"));
        assertTrue(granted(HttpMethod.DELETE, "/api/v1/clients/1/images/", "DELETE_CLIENTIMAGE"));
    }

    @Test
    void imagesCannotBeReachedByChangingTheCaseOfTheEntityType() {
        // Jersey matches ImagesApiResource's {entityType} in any case and the image services accept it
        for (String path : new String[] { "/api/v1/CLIENTS/1/images", "/api/v1/Clients/1/images/", "/api/v1/STAFF/1/images" }) {
            assertFalse(granted(HttpMethod.GET, path, "READ_CLIENT", "READ_STAFF"), path);
            assertTrue(granted(HttpMethod.GET, path, "READ_DOCUMENT"), path);
            assertFalse(granted(HttpMethod.POST, path, "READ_DOCUMENT"), path);
            assertFalse(granted(HttpMethod.PUT, path, "CREATE_CLIENTIMAGE", "CREATE_STAFFIMAGE"), path);
            assertFalse(granted(HttpMethod.DELETE, path, "READ_DOCUMENT"), path);
        }
        assertTrue(granted(HttpMethod.POST, "/api/v1/Clients/1/images", "CREATE_CLIENTIMAGE"));
        assertTrue(granted(HttpMethod.DELETE, "/api/v1/CLIENTS/1/images", "DELETE_CLIENTIMAGE"));
        assertTrue(granted(HttpMethod.PUT, "/api/v1/STAFF/1/images", "UPDATE_STAFFIMAGE"));
        assertFalse(granted(HttpMethod.PUT, "/api/v1/STAFF/1/images", "UPDATE_CLIENTIMAGE"));
    }

    @Test
    void imagesOfAnyOtherEntityTypeAreWrittenByTheAllFunctionsAuthoritiesOnly() {
        // no image permission exists for them, and the write service would store an orphan image
        assertFalse(granted(HttpMethod.POST, "/api/v1/loans/1/images", "CREATE_CLIENTIMAGE", "CREATE_STAFFIMAGE"));
        assertTrue(granted(HttpMethod.POST, "/api/v1/loans/1/images", "ALL_FUNCTIONS_WRITE"));
        // "staff" spelled with U+017F (long s), which String.equalsIgnoreCase - and so the image services - take for
        // "staff": the case-insensitive matcher folds it the same way
        assertTrue(granted(HttpMethod.DELETE, "/api/v1/%C5%BFtaff/1/images", "DELETE_STAFFIMAGE"));
        assertFalse(granted(HttpMethod.DELETE, "/api/v1/%C5%BFtaff/1/images", "DELETE_CLIENTIMAGE", "READ_DOCUMENT"));
        assertFalse(granted(HttpMethod.GET, "/api/v1/loans/1/images", "READ_LOAN"));
        assertTrue(granted(HttpMethod.GET, "/api/v1/loans/1/images", "READ_DOCUMENT"));
    }

    @Test
    void headRunsTheGetMethodSoItNeedsTheSameReadPermission() {
        for (String path : new String[] { "/api/v1/clients/1/images", "/api/v1/CLIENTS/1/images",
                "/api/v1/clients/1/documents/5/attachment" }) {
            assertFalse(granted(HttpMethod.HEAD, path, "READ_CLIENT"), path);
            assertTrue(granted(HttpMethod.HEAD, path, "READ_DOCUMENT"), path);
        }
    }

    @Test
    void unmatchedPathsStillOnlyNeedAnAuthenticatedUser() {
        // control: the harness grants what the catch-all rule grants
        assertTrue(granted(HttpMethod.GET, "/api/v1/offices", "READ_CLIENT"));
    }

    @Test
    void authenticationManagerPublishesFailureEvents() throws Exception {
        when(userDetailsService.loadUserByUsername("mifos"))
                .thenReturn(User.withUsername("mifos").password("{noop}password").authorities("ALL_FUNCTIONS").build());
        AuthenticationManager authenticationManager = securityConfig.authenticationManagerBean();

        assertThrows(BadCredentialsException.class,
                () -> authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("mifos", "wrong")));

        verify(loginAttemptEventListener).onAuthenticationEvent(isA(AuthenticationFailureBadCredentialsEvent.class));
        verify(loginAttemptEventListener, never()).onAuthenticationEvent(isA(AuthenticationSuccessEvent.class));
        verifyNothingPublishedThroughTheApplicationContext();
    }

    @Test
    void authenticationManagerPublishesSuccessEvents() throws Exception {
        when(userDetailsService.loadUserByUsername("mifos"))
                .thenReturn(User.withUsername("mifos").password("{noop}password").authorities("ALL_FUNCTIONS").build());
        AuthenticationManager authenticationManager = securityConfig.authenticationManagerBean();

        Authentication result = authenticationManager
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated("mifos", "password"));

        assertTrue(result.isAuthenticated());
        verify(loginAttemptEventListener).onAuthenticationEvent(isA(AuthenticationSuccessEvent.class));
        verify(loginAttemptEventListener, never()).onAuthenticationEvent(isA(AuthenticationFailureBadCredentialsEvent.class));
        // the asynchronous application multicaster would queue a pool task for every authenticated API request
        verifyNothingPublishedThroughTheApplicationContext();
    }

    @Test
    void aWrongPasswordAtTheAuthenticationEndpointIsCountedByTheLockout() throws Exception {
        // POST /v1/authentication through the real AuthenticationManager, down to the lockout's statement
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").name("default").timezoneId("UTC").build());
        when(lockoutConfiguration.isMaxLoginRetriesEnabled()).thenReturn(true);
        when(lockoutConfiguration.retrieveMaxLoginRetries()).thenReturn(3);
        when(appUserRepository.incrementFailedLoginAttempts("mifos")).thenReturn(1);
        when(userDetailsService.loadUserByUsername("mifos"))
                .thenReturn(User.withUsername("mifos").password("{noop}password").authorities("ALL_FUNCTIONS").build());
        @SuppressWarnings("unchecked")
        ToApiJsonSerializer<AuthenticatedUserData> serializer = mock(ToApiJsonSerializer.class);
        AuthenticationApiResource resource = new AuthenticationApiResource(securityConfig.authenticationManagerBean(), serializer,
                mock(SpringSecurityPlatformSecurityContext.class));

        assertThrows(BadCredentialsException.class, () -> resource.authenticate("{\"username\":\"mifos\",\"password\":\"wrong\"}"));

        verify(appUserRepository).incrementFailedLoginAttempts("mifos");
        verify(appUserRepository).lockWhenFailedLoginAttemptsReach("mifos", 3);
    }

    private void verifyNothingPublishedThroughTheApplicationContext() {
        verify(applicationContext, never()).publishEvent(any(Object.class));
        verify(applicationContext, never()).publishEvent(any(ApplicationEvent.class));
    }

    private AuthorizationManager<HttpServletRequest> buildAuthorizationManager() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        AuthenticationManagerBuilder authenticationManagerBuilder = new AuthenticationManagerBuilder(ObjectPostProcessor.identity());
        authenticationManagerBuilder.parentAuthenticationManager(mock(AuthenticationManager.class));
        Map<Class<?>, Object> sharedObjects = new HashMap<>();
        sharedObjects.put(ApplicationContext.class, context);
        HttpSecurity http = new HttpSecurity(ObjectPostProcessor.identity(), authenticationManagerBuilder, sharedObjects);

        SecurityFilterChain chain = securityConfig.filterChain(http);

        return chain.getFilters().stream().filter(AuthorizationFilter.class::isInstance).map(AuthorizationFilter.class::cast).findFirst()
                .orElseThrow().getAuthorizationManager();
    }

    private boolean granted(HttpMethod method, String uri, String... authorities) {
        MockHttpServletRequest request = new MockHttpServletRequest(method.name(), uri);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated("user", "password",
                AuthorityUtils.createAuthorityList(authorities));
        AuthorizationResult result = authorizationManager.authorize(() -> authentication, request);
        return result != null && result.isGranted();
    }
}
