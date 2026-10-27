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
     * The id of the account a failed login under this username is counted against: login retries enabled and not
     * locked; null for any other username. A plain read, so it locks nothing. An UPDATE naming the user by username
     * would: on InnoDB it locks the row of ANY user it names, even one its flags exclude, and so queues behind - and in
     * front of - the foreign-key share locks that user's in-flight transactions hold on the row (every row a user
     * creates references m_appuser). A wrong password naming an account that is never counted must not touch it.
     */
    @Query("SELECT appUser.id FROM AppUser appUser WHERE appUser.username = :username"
            + " AND appUser.loginRetryLimitEnabled = true AND appUser.accountNonLocked = true")
    Long findIdOfUserCountingFailedLogins(@Param("username") String username);

    /**
     * Counts one failed login of an account that has login retries enabled and is not locked, as one atomic statement:
     * a read-modify-write lets a burst of concurrent wrong passwords advance the count by one. The flags stay in the
     * WHERE clause, so an account they changed for since {@link #findIdOfUserCountingFailedLogins} is not counted. The
     * row lock it takes is held until the transaction ends, so a {@link #lockWhenFailedLoginAttemptsReach} in the same
     * transaction sees every earlier increment.
     *
     * @return the number of rows counted (0 or 1)
     */
    @Modifying
    @Query("UPDATE AppUser appUser SET appUser.failedLoginAttempts = appUser.failedLoginAttempts + 1 WHERE appUser.id = :id"
            + " AND appUser.loginRetryLimitEnabled = true AND appUser.accountNonLocked = true")
    int incrementFailedLoginAttempts(@Param("id") Long id);

    @Modifying
    @Query("UPDATE AppUser appUser SET appUser.accountNonLocked = false WHERE appUser.id = :id"
            + " AND appUser.loginRetryLimitEnabled = true AND appUser.accountNonLocked = true"
            + " AND appUser.failedLoginAttempts >= :maxRetries")
    int lockWhenFailedLoginAttemptsReach(@Param("id") Long id, @Param("maxRetries") int maxRetries);

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
