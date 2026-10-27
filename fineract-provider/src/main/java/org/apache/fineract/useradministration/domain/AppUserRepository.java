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
package org.apache.fineract.useradministration.domain;

import java.util.Collection;
import org.apache.fineract.infrastructure.security.domain.PlatformUserRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long>, JpaSpecificationExecutor<AppUser>, PlatformUserRepository {

    @Query("Select appUser from AppUser appUser where appUser.username = :username")
    AppUser findAppUserByName(@Param("username") String username);

    Collection<AppUser> findByOfficeId(Long officeId);

    @Query("Select appUser from AppUser appUser where appUser.email = :email and appUser.enabled = true and appUser.deleted = false")
    AppUser findActiveUserByEmail(@Param("email") String email);

    /**
     * Counts one failed login of an account that has login retries enabled and is not locked, as one atomic statement:
     * a read-modify-write lets a burst of concurrent wrong passwords advance the count by one. The row lock it takes is
     * held until the transaction ends, so a {@link #lockWhenFailedLoginAttemptsReach} in the same transaction sees
     * every earlier increment.
     *
     * @return the number of rows counted (0 or 1)
     */
    @Modifying
    @Query("UPDATE AppUser appUser SET appUser.failedLoginAttempts = appUser.failedLoginAttempts + 1 WHERE appUser.username = :username"
            + " AND appUser.loginRetryLimitEnabled = true AND appUser.accountNonLocked = true")
    int incrementFailedLoginAttempts(@Param("username") String username);

    @Modifying
    @Query("UPDATE AppUser appUser SET appUser.accountNonLocked = false WHERE appUser.username = :username"
            + " AND appUser.loginRetryLimitEnabled = true AND appUser.accountNonLocked = true"
            + " AND appUser.failedLoginAttempts >= :maxRetries")
    int lockWhenFailedLoginAttemptsReach(@Param("username") String username, @Param("maxRetries") int maxRetries);

    /**
     * Resets the failed login count and touches no other column, unlike saving an AppUser (a merge, which writes every
     * column of a possibly stale instance over the row).
     *
     * @return the number of rows reset (0 when the count already was 0)
     */
    @Modifying
    @Query("UPDATE AppUser appUser SET appUser.failedLoginAttempts = 0 WHERE appUser.id = :id AND appUser.failedLoginAttempts > 0")
    int resetFailedLoginAttempts(@Param("id") Long id);
}
