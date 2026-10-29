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
package org.apache.fineract.portfolio.client.service.search;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.PagedRequest;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.ClientRepository;
import org.apache.fineract.portfolio.client.service.search.domain.ClientTextSearch;
import org.apache.fineract.portfolio.client.service.search.mapper.ClientSearchDataMapper;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Permission;
import org.apache.fineract.useradministration.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.User;

@ExtendWith(MockitoExtension.class)
class ClientSearchServiceTest {

    private static final String HIERARCHY = ".1.";

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private ClientSearchDataMapper clientSearchDataMapper;

    private ClientSearchService service;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").name("default").timezoneId("UTC").build());
        service = new ClientSearchService(context, clientRepository, clientSearchDataMapper);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void searchWithoutReadClientIsRefused() {
        AppUser user = userWith("READ_LOAN", "READ_SAVINGSACCOUNT");
        when(context.authenticatedUser()).thenReturn(user);

        assertThrows(NoAuthorizationException.class, () -> service.searchByText(request("0777")));
        verifyNoInteractions(clientRepository);
    }

    @Test
    void searchWithReadClientRunsWithinTheOfficeHierarchy() {
        AppUser user = userWith("READ_CLIENT");
        when(context.authenticatedUser()).thenReturn(user);
        when(clientRepository.searchByText(eq("0777"), any(Pageable.class), eq(HIERARCHY))).thenReturn(Page.empty());

        service.searchByText(request("0777"));

        verify(clientRepository).searchByText(eq("0777"), any(Pageable.class), eq(HIERARCHY));
    }

    @Test
    void fullReadPermissionsKeepWorking() {
        for (String permission : List.of("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ")) {
            AppUser user = userWith(permission);
            when(context.authenticatedUser()).thenReturn(user);
            when(clientRepository.searchByText(eq("0777"), any(Pageable.class), eq(HIERARCHY))).thenReturn(Page.empty());

            service.searchByText(request("0777"));
        }
    }

    private static PagedRequest<ClientTextSearch> request(String text) {
        ClientTextSearch search = new ClientTextSearch();
        search.setText(text);
        PagedRequest<ClientTextSearch> request = new PagedRequest<>();
        request.setRequest(search);
        return request;
    }

    private static AppUser userWith(String... permissionCodes) {
        Role role = new Role("role", "role");
        for (String code : permissionCodes) {
            int separator = code.indexOf('_');
            role.updatePermission(new Permission("grouping", code.substring(separator + 1), code.substring(0, separator)), true);
        }
        Office office = mock(Office.class);
        lenient().when(office.getHierarchy()).thenReturn(HIERARCHY);
        User springUser = new User("user", "password", true, true, true, true, List.of());
        return new AppUser(office, springUser, Set.of(role), "user@example.com", "First", "Last", null, false, false);
    }
}
