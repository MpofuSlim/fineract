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
package org.apache.fineract.portfolio.search.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.search.data.AdHocQuerySearchRequest;
import org.apache.fineract.portfolio.search.data.SearchConditions;
import org.apache.fineract.portfolio.search.service.SearchReadService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Permission;
import org.apache.fineract.useradministration.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.core.userdetails.User;

@ExtendWith(MockitoExtension.class)
class SearchApiResourceTest {

    private static final String HIERARCHY = ".1.";

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private SearchReadService searchReadService;

    private SearchApiResource resource;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").name("default").timezoneId("UTC").build());
        resource = new SearchApiResource(context, searchReadService);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void fullReadPermissionsSearchEveryResourceType() {
        for (String permission : List.of("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ")) {
            SearchConditions conditions = search(null, permission);

            assertTrue(conditions.isClientSearch(), permission);
            assertTrue(conditions.isGroupSearch(), permission);
            assertTrue(conditions.isLoanSeach(), permission);
            assertTrue(conditions.isSavingSeach(), permission);
            assertTrue(conditions.isShareSeach(), permission);
            assertTrue(conditions.isClientIdentifierSearch(), permission);
        }
    }

    @Test
    void eachReadPermissionUnlocksItsOwnResourceType() {
        SearchConditions conditions = search(null, "READ_CLIENT", "READ_CLIENTIDENTIFIER", "READ_GROUP", "READ_LOAN", "READ_SAVINGSACCOUNT",
                "READ_SHAREACCOUNT");

        assertTrue(conditions.isClientSearch());
        assertTrue(conditions.isClientIdentifierSearch());
        assertTrue(conditions.isGroupSearch());
        assertTrue(conditions.isLoanSeach());
        assertTrue(conditions.isSavingSeach());
        assertTrue(conditions.isShareSeach());
    }

    @Test
    void sharesNeedReadShareAccountEvenWithEveryOtherReadPermission() {
        // READ_SHAREACCOUNT is seeded by 0240_innbucks_read_shareaccount_permission; before it, no role short of the
        // all-functions ones could be granted share results
        SearchConditions withoutShares = search(null, "READ_CLIENT", "READ_CLIENTIDENTIFIER", "READ_GROUP", "READ_LOAN",
                "READ_SAVINGSACCOUNT");
        assertFalse(withoutShares.isShareSeach());
        assertTrue(withoutShares.isSavingSeach());

        SearchConditions withShares = search(null, "READ_CLIENT", "READ_CLIENTIDENTIFIER", "READ_GROUP", "READ_LOAN", "READ_SAVINGSACCOUNT",
                "READ_SHAREACCOUNT");
        assertTrue(withShares.isShareSeach());
    }

    @Test
    void fullReadPermissionsReturnEveryKindOfGroupAndSavingsRow() {
        for (String permission : List.of("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ")) {
            SearchConditions conditions = search(null, permission);

            assertTrue(conditions.isCenterRows(), permission);
            assertTrue(conditions.isGroupRows(), permission);
            assertTrue(conditions.isSavingsDepositRows(), permission);
            assertTrue(conditions.isFixedDepositRows(), permission);
            assertTrue(conditions.isRecurringDepositRows(), permission);
        }
    }

    @Test
    void centersNeedReadCenterAndGroupsReadGroup() {
        // the group search returns centers too, and CentersApiResource checks READ_CENTER
        SearchConditions centersOnly = search(null, "READ_CENTER");
        assertTrue(centersOnly.isGroupSearch());
        assertTrue(centersOnly.isCenterRows());
        assertFalse(centersOnly.isGroupRows());

        SearchConditions groupsOnly = search("groups", "READ_GROUP");
        assertTrue(groupsOnly.isGroupSearch());
        assertFalse(groupsOnly.isCenterRows());
        assertTrue(groupsOnly.isGroupRows());
    }

    @Test
    void fixedAndRecurringDepositsNeedTheirOwnReadPermission() {
        // the savings search returns every m_savings_account row, and the deposit APIs check their own permission
        SearchConditions fixedOnly = search(null, "READ_FIXEDDEPOSITACCOUNT");
        assertTrue(fixedOnly.isSavingSeach());
        assertFalse(fixedOnly.isSavingsDepositRows());
        assertTrue(fixedOnly.isFixedDepositRows());
        assertFalse(fixedOnly.isRecurringDepositRows());
        assertFalse(fixedOnly.isClientSearch());

        SearchConditions savingsOnly = search("savings", "READ_SAVINGSACCOUNT");
        assertTrue(savingsOnly.isSavingsDepositRows());
        assertFalse(savingsOnly.isFixedDepositRows());
        assertFalse(savingsOnly.isRecurringDepositRows());

        SearchConditions recurringOnly = search("savings", "READ_RECURRINGDEPOSITACCOUNT");
        assertTrue(recurringOnly.isSavingSeach());
        assertFalse(recurringOnly.isSavingsDepositRows());
        assertTrue(recurringOnly.isRecurringDepositRows());
    }

    @Test
    void everyPermissionTheSearchChecksIsSeeded() throws IOException {
        // a permission that is not in m_permission cannot be added to a role, so a type gated on it would be lost to
        // every role short of the all-functions ones. Captures what the search actually checks, then looks for it in
        // the tenant changelogs.
        Set<String> checked = new TreeSet<>();
        AppUser user = mock(AppUser.class);
        Office office = mock(Office.class);
        when(user.getOffice()).thenReturn(office);
        when(office.getHierarchy()).thenReturn(HIERARCHY);
        when(user.hasNotPermissionForAnyOf(any(String[].class))).thenAnswer(invocation -> {
            for (Object argument : invocation.getArguments()) {
                if (argument instanceof String[] codes) {
                    checked.addAll(List.of(codes));
                } else {
                    checked.add((String) argument);
                }
            }
            return false;
        });
        when(context.authenticatedUser()).thenReturn(user);

        resource.searchData("Petra", null, false);

        assertTrue(
                checked.containsAll(List.of("READ_CLIENT", "READ_CLIENTIDENTIFIER", "READ_GROUP", "READ_CENTER", "READ_LOAN",
                        "READ_SAVINGSACCOUNT", "READ_FIXEDDEPOSITACCOUNT", "READ_RECURRINGDEPOSITACCOUNT", "READ_SHAREACCOUNT")),
                checked.toString());
        StringBuilder changelogs = new StringBuilder();
        for (Resource changelog : new PathMatchingResourcePatternResolver().getResources("classpath*:db/changelog/tenant/**/*.xml")) {
            changelogs.append(changelog.getContentAsString(StandardCharsets.UTF_8));
        }
        for (String code : checked) {
            assertTrue(changelogs.indexOf("<column name=\"code\" value=\"" + code + "\"/>") >= 0, code + " is not seeded");
        }
    }

    @Test
    void unreadableTypesAreDroppedFromTheDefaultSearch() {
        SearchConditions conditions = search(null, "READ_CLIENT", "READ_LOAN");

        assertTrue(conditions.isClientSearch());
        assertTrue(conditions.isLoanSeach());
        assertFalse(conditions.isGroupSearch());
        assertFalse(conditions.isSavingSeach());
        assertFalse(conditions.isShareSeach());
        assertFalse(conditions.isClientIdentifierSearch());
        assertEquals("Petra", conditions.getSearchQuery());
        assertEquals(HIERARCHY, conditions.getHierarchy());
    }

    @Test
    void unreadableTypesAreDroppedFromAnExplicitResourceList() {
        SearchConditions conditions = search("clients,groups", "READ_GROUP", "READ_LOAN");

        assertTrue(conditions.isGroupSearch());
        assertFalse(conditions.isClientSearch());
        // readable but not requested
        assertFalse(conditions.isLoanSeach());
    }

    @Test
    void searchWithNoReadableRequestedTypeIsRefused() {
        givenUser("READ_CLIENT");

        assertThrows(NoAuthorizationException.class, () -> resource.searchData("Petra", "loans,savings", false));
        verifyNoInteractions(searchReadService);
    }

    @Test
    void defaultSearchWithoutAnyReadPermissionIsRefused() {
        givenUser("READ_OFFICE");

        assertThrows(NoAuthorizationException.class, () -> resource.searchData("Petra", null, false));
        verifyNoInteractions(searchReadService);
    }

    @Test
    void advancedSearchRequiresReadLoan() {
        AdHocQuerySearchRequest request = mock(AdHocQuerySearchRequest.class);

        givenUser("READ_CLIENT");
        assertThrows(NoAuthorizationException.class, () -> resource.advancedSearch(request));
        verifyNoInteractions(searchReadService);

        givenUser("READ_LOAN");
        resource.advancedSearch(request);
        verify(searchReadService).retrieveAdHocQueryMatchingData(request);
    }

    private SearchConditions search(String resourceTypes, String... permissionCodes) {
        givenUser(permissionCodes);
        resource.searchData("Petra", resourceTypes, false);

        ArgumentCaptor<SearchConditions> captor = ArgumentCaptor.forClass(SearchConditions.class);
        verify(searchReadService, atLeastOnce()).retriveMatchingData(captor.capture());
        return captor.getValue();
    }

    private void givenUser(String... permissionCodes) {
        AppUser user = userWith(permissionCodes);
        when(context.authenticatedUser()).thenReturn(user);
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
