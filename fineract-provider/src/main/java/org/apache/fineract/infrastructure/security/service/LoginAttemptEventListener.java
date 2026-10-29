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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.apache.fineract.useradministration.service.AppUserConstants;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Counts failed staff logins and locks the account once the configured maximum is reached. Inert unless an operator
 * enables BOTH the global "max-login-retry-attempts" configuration (seeded disabled) and the user's own
 * is_login_retries_enabled flag (default false).
 * <p>
 * The Basic-auth AuthenticationManager (SecurityConfig) delivers its authentication events straight to
 * {@link #onAuthenticationEvent(Object)}, on the authenticating thread. Not through the application event multicaster:
 * that would run the listener on the FineractEvent pool, where the tenant is not set, and queue a pool task for every
 * API request. Every Basic-auth API request authenticates, so the success path must stay free: no transaction and no
 * database access while the principal has no failed attempts.
 * <p>
 * The bookkeeping never changes the outcome of an authentication: a fault here is logged and the original result or
 * exception stands.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptEventListener {

    private static final String USERS_CACHE = "users";
    private static final String USERS_BY_USERNAME_CACHE = "usersByUsername";

    private final ConfigurationDomainService configurationDomainService;
    private final AppUserRepository appUserRepository;
    private final CacheManager cacheManager;
    private final TransactionTemplate transactionTemplate;

    public void onAuthenticationEvent(final Object event) {
        try {
            if (ThreadLocalContextUtil.getTenant() == null) {
                // nothing to count in: the authentication failed before a tenant was resolved
                return;
            }
            if (event instanceof AuthenticationFailureBadCredentialsEvent failureEvent) {
                onAuthenticationFailure(failureEvent);
            } else if (event instanceof AuthenticationSuccessEvent successEvent) {
                onAuthenticationSuccess(successEvent);
            }
        } catch (RuntimeException e) {
            log.warn("Login attempt bookkeeping failed; the authentication result is unchanged", e);
        }
    }

    /**
     * Only bad credentials count: every other failure (a locked, disabled or expired account, or a fault while loading
     * the user) is not a wrong password, and counting it could lock an account that presented the right one.
     */
    void onAuthenticationFailure(final AuthenticationFailureBadCredentialsEvent event) {
        if (!configurationDomainService.isMaxLoginRetriesEnabled()) {
            return;
        }
        final Integer maxRetries = configurationDomainService.retrieveMaxLoginRetries();
        if (maxRetries == null || maxRetries <= 0) {
            return;
        }

        final Authentication authentication = event.getAuthentication();
        if (authentication == null || !StringUtils.hasText(authentication.getName())) {
            return;
        }
        final String username = authentication.getName();
        if (AppUserConstants.SYSTEM_USER_NAME.equalsIgnoreCase(username)) {
            // never has login retries enabled (AppUser.resolveLoginRetryLimitEnabled)
            return;
        }

        // A plain read first, outside any transaction: it locks nothing. Only an account that is counted is then
        // updated, so a wrong password naming one that is not (an unknown user, retries disabled - the middleware's
        // service accounts - or already locked) opens no transaction and never queues on that user's row behind its
        // in-flight transactions (see AppUserRepository.findIdOfUserCountingFailedLogins).
        final Long userId = appUserRepository.findIdOfUserCountingFailedLogins(username);
        if (userId == null) {
            return;
        }

        // An atomic increment, then the lock decision, in one transaction. The increment's row lock is held until
        // commit, so concurrent wrong passwords each count and the lock sees every earlier increment.
        final boolean counted = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (appUserRepository.incrementFailedLoginAttempts(userId) == 0) {
                // its retries were disabled, or it was locked, since the read
                return false;
            }
            appUserRepository.lockWhenFailedLoginAttemptsReach(userId, maxRetries);
            return true;
        }));
        if (counted) {
            evictUserCaches();
        }
    }

    void onAuthenticationSuccess(final AuthenticationSuccessEvent event) {
        final Authentication authentication = event.getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUser user)) {
            return;
        }

        if (user.getFailedLoginAttempts() <= 0) {
            return;
        }

        // A targeted statement, never a save of the principal: it is the cached, detached AppUser, and saving it would
        // merge every one of its possibly stale columns (password, roles, lock) over the row.
        transactionTemplate.execute(status -> appUserRepository.resetFailedLoginAttempts(user.getId()));
        // Even when another request reset the row first: the cached principal still carries failed attempts, and
        // would send every later request down this path again.
        evictUserCaches();
    }

    private void evictUserCaches() {
        Cache usersCache = cacheManager.getCache(USERS_CACHE);
        if (usersCache != null) {
            usersCache.clear();
        }
        Cache usersByUsernameCache = cacheManager.getCache(USERS_BY_USERNAME_CACHE);
        if (usersByUsernameCache != null) {
            usersByUsernameCache.clear();
        }
    }
}
