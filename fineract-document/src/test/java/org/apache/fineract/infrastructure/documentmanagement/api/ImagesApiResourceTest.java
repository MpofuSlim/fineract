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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.fineract.command.core.CommandDispatcher;
import org.apache.fineract.infrastructure.contentstore.processor.Base64DecoderContentProcessor;
import org.apache.fineract.infrastructure.contentstore.processor.Base64EncoderContentProcessor;
import org.apache.fineract.infrastructure.contentstore.processor.DataUrlDecoderContentProcessor;
import org.apache.fineract.infrastructure.contentstore.processor.DataUrlEncoderContentProcessor;
import org.apache.fineract.infrastructure.contentstore.processor.ImageResizeContentProcessor;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.documentmanagement.command.ImageDeleteCommand;
import org.apache.fineract.infrastructure.documentmanagement.data.ImageCreateResponse;
import org.apache.fineract.infrastructure.documentmanagement.data.ImageDeleteResponse;
import org.apache.fineract.infrastructure.documentmanagement.service.ImageReadPlatformService;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.Permission;
import org.apache.fineract.useradministration.domain.Role;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.User;

/**
 * Jersey matches the {entityType} of /v1/{entityType}/{entityId}/images in any case and the image services accept it
 * case-insensitively, so the resource checks the same permissions SecurityConfig does, whatever the spelling.
 */
@ExtendWith(MockitoExtension.class)
class ImagesApiResourceTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private ImageReadPlatformService imageReadPlatformService;
    @Mock
    private CommandDispatcher dispatcher;

    private ImagesApiResource resource;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil
                .setTenant(FineractPlatformTenant.builder().id(1L).tenantIdentifier("default").name("default").timezoneId("UTC").build());
        resource = new ImagesApiResource(context, imageReadPlatformService, dispatcher, mock(ImageResizeContentProcessor.class),
                mock(Base64EncoderContentProcessor.class), mock(DataUrlEncoderContentProcessor.class),
                mock(Base64DecoderContentProcessor.class), mock(DataUrlDecoderContentProcessor.class), mock(FileUploadValidator.class));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void readingNeedsReadDocumentWhateverTheCaseOfTheEntityType() {
        givenUser("READ_CLIENT", "READ_STAFF", "CREATE_CLIENTIMAGE");

        for (String entityType : List.of("clients", "CLIENTS", "Staff")) {
            assertThrows(NoAuthorizationException.class, () -> resource.retrieveImage(entityType, 1L, null, null, null, null), entityType);
        }
        verifyNoInteractions(imageReadPlatformService);
    }

    @Test
    void readDocumentAndTheAllFunctionsReadersReachTheImage() {
        RuntimeException reached = new IllegalStateException("reached the read service");
        when(imageReadPlatformService.retrieveImage("CLIENTS", 1L)).thenThrow(reached);

        for (String permission : List.of("READ_DOCUMENT", "ALL_FUNCTIONS_READ", "ALL_FUNCTIONS")) {
            givenUser(permission);
            assertSame(reached,
                    assertThrows(IllegalStateException.class, () -> resource.retrieveImage("CLIENTS", 1L, null, null, null, null)));
        }
        verify(imageReadPlatformService, times(3)).retrieveImage("CLIENTS", 1L);
    }

    @Test
    void writesNeedTheEntitysImagePermissionWhateverTheCaseOfTheEntityType() {
        givenUser("READ_DOCUMENT", "CREATE_STAFFIMAGE", "DELETE_STAFFIMAGE");

        assertThrows(NoAuthorizationException.class, () -> createMultipart("CLIENTS"));
        assertThrows(NoAuthorizationException.class, () -> resource.createImage("Clients", 1L, body()));
        assertThrows(NoAuthorizationException.class, () -> updateMultipart("CLIENTS"));
        assertThrows(NoAuthorizationException.class, () -> resource.updateImage("STAFF", 1L, body()));
        assertThrows(NoAuthorizationException.class, () -> resource.deleteImage("Clients", 1L));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void eachWriteNeedsItsOwnAction() {
        givenDispatchSucceeds();

        givenUser("CREATE_CLIENTIMAGE");
        createMultipart("CLIENTS");
        // PUT is an update, as in SecurityConfig, although it runs the create command
        assertThrows(NoAuthorizationException.class, () -> updateMultipart("CLIENTS"));
        assertThrows(NoAuthorizationException.class, () -> resource.deleteImage("CLIENTS", 1L));

        givenUser("UPDATE_STAFFIMAGE", "DELETE_STAFFIMAGE");
        updateMultipart("STAFF");
        resource.deleteImage("Staff", 1L);

        verify(dispatcher, times(3)).dispatch(any());
    }

    @Test
    void imagesOfAnyOtherEntityTypeAreWrittenByTheAllFunctionsAuthoritiesOnly() {
        givenUser("CREATE_CLIENTIMAGE", "CREATE_STAFFIMAGE", "DELETE_CLIENTIMAGE", "DELETE_STAFFIMAGE");
        assertThrows(NoAuthorizationException.class, () -> createMultipart("loans"));
        assertThrows(NoAuthorizationException.class, () -> resource.deleteImage("loans", 1L));
        verifyNoInteractions(dispatcher);

        givenDispatchSucceeds();
        for (String permission : List.of("ALL_FUNCTIONS_WRITE", "ALL_FUNCTIONS")) {
            givenUser(permission);
            createMultipart("loans");
        }
        verify(dispatcher, times(2)).dispatch(any());
    }

    private ImageCreateResponse createMultipart(String entityType) {
        return resource.createImage(entityType, 1L, 3L, body(), mock(FormDataContentDisposition.class), mock(FormDataBodyPart.class));
    }

    private ImageCreateResponse updateMultipart(String entityType) {
        return resource.updateImage(entityType, 1L, 3L, body(), mock(FormDataContentDisposition.class), mock(FormDataBodyPart.class));
    }

    private static InputStream body() {
        return new ByteArrayInputStream(new byte[3]);
    }

    private void givenDispatchSucceeds() {
        when(dispatcher.dispatch(any())).thenAnswer(invocation -> invocation.getArgument(0) instanceof ImageDeleteCommand
                ? (Supplier<Object>) () -> ImageDeleteResponse.builder().build()
                : (Supplier<Object>) () -> ImageCreateResponse.builder().build());
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
        User springUser = new User("user", "password", true, true, true, true, List.of());
        return new AppUser(mock(Office.class), springUser, Set.of(role), "user@example.com", "First", "Last", null, false, false);
    }
}
