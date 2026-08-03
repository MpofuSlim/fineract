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
package org.apache.fineract.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.core.service.database.DatabaseTypeResolver;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.utils.ColumnValidator;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class NotificationReadPlatformServiceImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlatformSecurityContext context;

    @Mock
    private ColumnValidator columnValidator;

    @Mock
    private PaginationHelper paginationHelper;

    @Mock
    private DatabaseTypeResolver databaseTypeResolver;

    @Mock
    private AppUser appUser;

    private NotificationReadPlatformServiceImpl underTest;

    @BeforeEach
    void setUp() {
        DatabaseSpecificSQLGenerator sqlGenerator = new DatabaseSpecificSQLGenerator(databaseTypeResolver, null);
        underTest = new NotificationReadPlatformServiceImpl(jdbcTemplate, context, columnValidator, paginationHelper, sqlGenerator);
        when(context.authenticatedUser()).thenReturn(appUser);
        when(appUser.getId()).thenReturn(1L);
    }

    @Test
    void defaultOrderingIsAppliedWhenNoOrderByIsPassed() {
        underTest.getAllUnreadNotifications(SearchParameters.builder().build());

        String sql = executedSql();
        assertTrue(sql.endsWith("order by nm.created_at desc"), "Expected default newest-first ordering: " + sql);
        assertEquals(1, orderByCount(sql), "Expected exactly one order by clause: " + sql);
    }

    @Test
    void callerOrderByReplacesTheDefaultInsteadOfStackingASecondClause() {
        // Regression: the base SQL used to carry a hardcoded "order by" and a
        // caller-supplied orderBy appended a second one — malformed SQL, so any
        // client passing orderBy got a 500 on every notification fetch.
        underTest.getAllUnreadNotifications(SearchParameters.builder().orderBy("createdAt").sortOrder("DESC").build());

        String sql = executedSql();
        assertTrue(sql.endsWith("order by createdAt DESC"), "Expected the caller's ordering only: " + sql);
        assertEquals(1, orderByCount(sql), "Expected exactly one order by clause: " + sql);
    }

    @Test
    void getAllNotificationsAppliesTheSameSingleOrdering() {
        underTest.getAllNotifications(SearchParameters.builder().orderBy("createdAt").build());

        String sql = executedSql();
        assertTrue(sql.endsWith("order by createdAt"), "Expected the caller's ordering only: " + sql);
        assertEquals(1, orderByCount(sql), "Expected exactly one order by clause: " + sql);
    }

    private String executedSql() {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(paginationHelper).fetchPage(eq(jdbcTemplate), sqlCaptor.capture(), any(Object[].class), any(RowMapper.class));
        return sqlCaptor.getValue();
    }

    private static int orderByCount(String sql) {
        return sql.toLowerCase(Locale.ROOT).split("order by", -1).length - 1;
    }
}
