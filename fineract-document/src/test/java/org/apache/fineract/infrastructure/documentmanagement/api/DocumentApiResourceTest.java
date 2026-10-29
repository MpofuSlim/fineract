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
package org.apache.fineract.infrastructure.documentmanagement.api;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;
import org.apache.fineract.command.core.CommandDispatcher;
import org.apache.fineract.infrastructure.contentstore.detector.ContentDetectorManager;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.documentmanagement.data.DocumentContent;
import org.apache.fineract.infrastructure.documentmanagement.data.DocumentData;
import org.apache.fineract.infrastructure.documentmanagement.service.DocumentReadPlatformService;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Permission;
import org.apache.fineract.useradministration.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;

@ExtendWith(MockitoExtension.class)
class DocumentApiResourceTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private DocumentReadPlatformService documentReadPlatformService;

    private DocumentApiResource resource;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").name("default").timezoneId("UTC").build());
        resource = new DocumentApiResource(context, documentReadPlatformService, mock(FileUploadValidator.class),
                mock(ContentDetectorManager.class), mock(CommandDispatcher.class));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void readsAreRefusedWithoutReadDocument() {
        AppUser user = userWith("READ_CLIENT", "READ_LOAN", "CREATE_DOCUMENT");
        when(context.authenticatedUser()).thenReturn(user);

        assertThrows(NoAuthorizationException.class, () -> resource.retrieveAllDocuments("clients", 1L));
        assertThrows(NoAuthorizationException.class, () -> resource.getDocument("clients", 1L, 5L));
        assertThrows(NoAuthorizationException.class, () -> resource.downloadFile("clients", 1L, 5L));

        verifyNoInteractions(documentReadPlatformService);
    }

    @Test
    void readDocumentAllowsListDetailAndDownload() {
        AppUser user = userWith("READ_DOCUMENT");
        when(context.authenticatedUser()).thenReturn(user);
        DocumentData document = mock(DocumentData.class);
        when(documentReadPlatformService.retrieveAllDocuments("loans", 1L)).thenReturn(List.of(document));
        when(documentReadPlatformService.retrieveDocument("loans", 1L, 5L)).thenReturn(document);
        when(documentReadPlatformService.retrieveDocumentContent("loans", 1L, 5L)).thenReturn(DocumentContent.builder()
                .fileName("statement.pdf").contentType("application/pdf").size(3L).stream(new ByteArrayInputStream(new byte[3])).build());

        assertSame(document, resource.retrieveAllDocuments("loans", 1L).get(0));
        assertSame(document, resource.getDocument("loans", 1L, 5L));
        resource.downloadFile("loans", 1L, 5L);

        verify(documentReadPlatformService).retrieveDocumentContent("loans", 1L, 5L);
    }

    @Test
    void allFunctionsAndAllFunctionsReadKeepWorking() {
        for (String permission : List.of("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ")) {
            AppUser user = userWith(permission);
            when(context.authenticatedUser()).thenReturn(user);
            resource.getDocument("clients", 1L, 5L);
        }
        verify(documentReadPlatformService, times(2)).retrieveDocument("clients", 1L, 5L);
    }

    private static AppUser userWith(String... permissionCodes) {
        Role role = new Role("role", "role");
        for (String code : permissionCodes) {
            int separator = code.indexOf('_');
            role.updatePermission(new Permission("grouping", code.substring(separator + 1), code.substring(0, separator)), true);
        }
        User springUser = new User("user", "password", true, true, true, true, List.of());
        return new AppUser(mock(Office.class), springUser, Set.of(role), "user@example.com", "First", "Last", null, false, false);
    }
}
