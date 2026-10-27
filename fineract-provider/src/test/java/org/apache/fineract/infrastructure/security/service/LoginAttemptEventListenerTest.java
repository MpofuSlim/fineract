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
package org.apache.fineract.infrastructure.security.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureCredentialsExpiredEvent;
import org.springframework.security.authentication.event.AuthenticationFailureDisabledEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.authentication.event.AuthenticationFailureServiceExceptionEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class LoginAttemptEventListenerTest {

    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache usersCache;
    @Mock
    private Cache usersByUsernameCache;
    @Mock
    private PlatformTransactionManager transactionManager;

    private LoginAttemptEventListener listener;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").name("default").timezoneId("UTC").build());
        listener = new LoginAttemptEventListener(configurationDomainService, appUserRepository, cacheManager,
                new TransactionTemplate(transactionManager));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void badCredentialsCountAndLockInOneTransaction() {
        givenLockoutEnabled(3);
        givenCaches();
        when(appUserRepository.incrementFailedLoginAttempts("user")).thenReturn(1);

        listener.onAuthenticationEvent(badCredentials("user"));

        InOrder inOrder = inOrder(transactionManager, appUserRepository);
        inOrder.verify(transactionManager).getTransaction(any());
        inOrder.verify(appUserRepository).incrementFailedLoginAttempts("user");
        inOrder.verify(appUserRepository).lockWhenFailedLoginAttemptsReach("user", 3);
        inOrder.verify(transactionManager).commit(any());
        verify(usersCache).clear();
        verify(usersByUsernameCache).clear();
    }

    @Test
    void anAccountThatWasNotCountedIsNotLockedAndTheCachesAreKept() {
        // unknown user, login retries disabled for it, or already locked: the increment matches no row
        givenLockoutEnabled(3);
        when(appUserRepository.incrementFailedLoginAttempts("user")).thenReturn(0);

        listener.onAuthenticationEvent(badCredentials("user"));

        verify(appUserRepository, never()).lockWhenFailedLoginAttemptsReach(anyString(), anyInt());
        verifyNoInteractions(cacheManager);
    }

    @Test
    void nothingIsCountedWhileTheGlobalLimitIsDisabled() {
        when(configurationDomainService.isMaxLoginRetriesEnabled()).thenReturn(false);

        listener.onAuthenticationEvent(badCredentials("user"));

        verifyNoInteractions(appUserRepository, transactionManager, cacheManager);
    }

    @Test
    void theSystemUserIsNeverCounted() {
        givenLockoutEnabled(3);

        listener.onAuthenticationEvent(badCredentials("system"));

        verifyNoInteractions(appUserRepository, transactionManager, cacheManager);
    }

    @Test
    void onlyBadCredentialsCount() {
        // none of these is a wrong password: a fault while loading the user must never lock an account that presented
        // the right one
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("user", "password");
        for (Object event : List.of(
                new AuthenticationFailureServiceExceptionEvent(token, new InternalAuthenticationServiceException("database down")),
                new AuthenticationFailureLockedEvent(token, new LockedException("locked")),
                new AuthenticationFailureDisabledEvent(token, new DisabledException("disabled")),
                new AuthenticationFailureCredentialsExpiredEvent(token, new CredentialsExpiredException("expired")))) {
            listener.onAuthenticationEvent(event);
        }

        verifyNoInteractions(configurationDomainService, appUserRepository, transactionManager, cacheManager);
    }

    @Test
    void successResetsTheCountWithATargetedStatementNotASaveOfThePrincipal() {
        givenCaches();
        AppUser user = buildUser("user", 2);

        listener.onAuthenticationEvent(success(user));

        verify(appUserRepository).resetFailedLoginAttempts(17L);
        verify(appUserRepository, never()).save(any());
        verify(appUserRepository, never()).saveAndFlush(any());
        verify(transactionManager).commit(any());
        verify(usersCache).clear();
        verify(usersByUsernameCache).clear();
    }

    @Test
    void successWithNoFailedAttemptsOpensNoTransactionAndTouchesNoDatabase() {
        // every Basic-auth API request authenticates. Through a transactional proxy, so a @Transactional added to the
        // listener (which would open a transaction and take a connection before the zero-attempt return) fails here.
        ProxyFactory proxyFactory = new ProxyFactory(listener);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        LoginAttemptEventListener proxied = (LoginAttemptEventListener) proxyFactory.getProxy();

        proxied.onAuthenticationEvent(success(buildUser("user", 0)));

        verifyNoInteractions(transactionManager, appUserRepository, configurationDomainService, cacheManager);
    }

    @Test
    void withoutATenantNothingIsTouched() {
        AppUser user = buildUser("user", 2);
        ThreadLocalContextUtil.reset();

        listener.onAuthenticationEvent(badCredentials("user"));
        listener.onAuthenticationEvent(success(user));

        verifyNoInteractions(configurationDomainService, appUserRepository, transactionManager, cacheManager);
    }

    // The bookkeeping runs inside ProviderManager.authenticate: an exception escaping it would replace the
    // authentication's own result, e.g. turn a 401 into a 500.

    @Test
    void aConfigurationFaultNeverEscapes() {
        when(configurationDomainService.isMaxLoginRetriesEnabled()).thenThrow(new IllegalStateException("no configuration"));

        assertDoesNotThrow(() -> listener.onAuthenticationEvent(badCredentials("user")));
    }

    @Test
    void aDatabaseFaultWhileCountingNeverEscapes() {
        givenLockoutEnabled(3);
        when(appUserRepository.incrementFailedLoginAttempts("user")).thenThrow(new DataAccessResourceFailureException("database down"));

        assertDoesNotThrow(() -> listener.onAuthenticationEvent(badCredentials("user")));
        verify(transactionManager).rollback(any());
    }

    @Test
    void aDatabaseFaultWhileResettingNeverEscapes() {
        when(appUserRepository.resetFailedLoginAttempts(17L)).thenThrow(new DataAccessResourceFailureException("database down"));

        assertDoesNotThrow(() -> listener.onAuthenticationEvent(success(buildUser("user", 2))));
    }

    @Test
    void ignoresOtherEvents() {
        listener.onAuthenticationEvent("not an authentication event");

        verifyNoInteractions(configurationDomainService, appUserRepository, transactionManager, cacheManager);
    }

    private void givenLockoutEnabled(int maxRetries) {
        when(configurationDomainService.isMaxLoginRetriesEnabled()).thenReturn(true);
        when(configurationDomainService.retrieveMaxLoginRetries()).thenReturn(maxRetries);
    }

    private void givenCaches() {
        when(cacheManager.getCache("users")).thenReturn(usersCache);
        when(cacheManager.getCache("usersByUsername")).thenReturn(usersByUsernameCache);
    }

    private static AuthenticationFailureBadCredentialsEvent badCredentials(String username) {
        return new AuthenticationFailureBadCredentialsEvent(new UsernamePasswordAuthenticationToken(username, "bad"),
                new BadCredentialsException("bad"));
    }

    private static AuthenticationSuccessEvent success(AppUser user) {
        return new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken(user, "pass", List.of()));
    }

    private static AppUser buildUser(String username, int failedLoginAttempts) {
        Office office = mock(Office.class);
        User springUser = new User(username, "pass", true, true, true, true, List.of(new SimpleGrantedAuthority("ALL_FUNCTIONS")));
        AppUser user = new AppUser(office, springUser, new HashSet<>(), "user@example.com", "First", "Last", null, false, false);
        user.setId(17L);
        user.updateLoginRetryLimitEnabled(true);
        for (int i = 0; i < failedLoginAttempts; i++) {
            user.registerFailedLoginAttempt(100);
        }
        return user;
    }
}
