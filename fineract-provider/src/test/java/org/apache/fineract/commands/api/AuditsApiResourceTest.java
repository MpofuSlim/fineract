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
package org.apache.fineract.commands.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.UriInfo;
import org.apache.fineract.commands.data.request.AuditRequest;
import org.apache.fineract.commands.service.AuditReadPlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.utils.SQLBuilder;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditsApiResourceTest {

    @Mock
    private PlatformSecurityContext context;
    @Mock
    private AuditReadPlatformService auditReadPlatformService;
    @Mock
    private ApiRequestParameterHelper apiRequestParameterHelper;
    @Mock
    private ToApiJsonSerializer<String> toApiJsonSerializer;
    @Mock
    private UriInfo uriInfo;

    private AuditsApiResource resource;

    @BeforeEach
    void setUp() {
        resource = new AuditsApiResource(context, auditReadPlatformService, apiRequestParameterHelper, toApiJsonSerializer);
        when(context.authenticatedUser()).thenReturn(mock(AppUser.class));
    }

    @Test
    void processingResultFiltersOnStatusAsAnInteger() {
        SQLBuilder criteria = search(request(null, "2"));

        assertEquals(" WHERE  aud.status = ?", criteria.getSQLTemplate());
        assertArrayEquals(new Object[] { 2 }, criteria.getArguments());
    }

    @Test
    void statusIsBoundAsAnInteger() {
        SQLBuilder criteria = search(request(" 5 ", null));

        assertEquals(" WHERE  aud.status = ?", criteria.getSQLTemplate());
        assertArrayEquals(new Object[] { 5 }, criteria.getArguments());
    }

    @Test
    void blankValuesAddNoCriteria() {
        SQLBuilder criteria = search(request("", " "));

        assertEquals("", criteria.getSQLTemplate());
        assertArrayEquals(new Object[0], criteria.getArguments());
    }

    @Test
    void nonNumericProcessingResultIsAValidationError() {
        PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> resource.retrieveAuditEntries(uriInfo, request(null, "PROCESSED"), null, null, null, null, false));

        assertEquals("processingResult", exception.getErrors().get(0).getParameterName());
        verifyNoInteractions(auditReadPlatformService);
    }

    @Test
    void nonNumericStatusIsAValidationError() {
        PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> resource.retrieveAuditEntries(uriInfo, request("awaiting", null), null, null, null, null, false));

        assertEquals("status", exception.getErrors().get(0).getParameterName());
        verifyNoInteractions(auditReadPlatformService);
    }

    private SQLBuilder search(AuditRequest auditRequest) {
        when(apiRequestParameterHelper.process(any())).thenReturn(mock(ApiRequestJsonSerializationSettings.class));

        resource.retrieveAuditEntries(uriInfo, auditRequest, null, null, null, null, false);

        ArgumentCaptor<SQLBuilder> captor = ArgumentCaptor.forClass(SQLBuilder.class);
        verify(auditReadPlatformService).retrieveAuditEntries(captor.capture(), anyBoolean());
        return captor.getValue();
    }

    private static AuditRequest request(String status, String processingResult) {
        AuditRequest auditRequest = new AuditRequest();
        auditRequest.setStatus(status);
        auditRequest.setProcessingResult(processingResult);
        return auditRequest;
    }
}
