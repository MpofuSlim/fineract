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
package org.apache.fineract.infrastructure.contentstore.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The content-store exceptions all extend {@code AbstractPlatformException}, for which no mapper exists, so before
 * these mappers every failure on the document and image endpoints — a rejected file name as much as an unwritable
 * volume — returned an identical bare 500.
 */
class ContentStoreExceptionMappersTest {

    @Test
    void policyViolationIsABadRequest() {
        Response response = new ContentPolicyExceptionMapper()
                .toResponse(new ContentPolicyException("File name not allowed: APPRAISAL.PDF"));

        assertEquals(400, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());

        ApiGlobalErrorResponse entity = (ApiGlobalErrorResponse) response.getEntity();
        assertEquals("error.msg.content.policy", entity.getUserMessageGlobalisationCode());
        assertEquals("File name not allowed: APPRAISAL.PDF", entity.getDefaultUserMessage());
    }

    @Test
    void detectionFailureIsABadRequest() {
        Response response = new ContentDetectorExceptionMapper()
                .toResponse(new ContentDetectorException(new IOException("could not detect")));

        assertEquals(400, response.getStatus());

        ApiGlobalErrorResponse entity = (ApiGlobalErrorResponse) response.getEntity();
        assertEquals("error.msg.content.detector", entity.getUserMessageGlobalisationCode());
        assertEquals("could not detect", entity.getDefaultUserMessage());
    }

    @Test
    void storeFailureStaysAServerErrorButIsLabelled() {
        Response response = new ContentStoreExceptionMapper()
                .toResponse(new ContentStoreException(new IOException("/fineract-content: permission denied")));

        assertEquals(500, response.getStatus());

        // serverSideError() puts the specific code in errors[0] and a generic one at the top level.
        ApiGlobalErrorResponse entity = (ApiGlobalErrorResponse) response.getEntity();
        assertEquals(1, entity.getErrors().size());
        assertEquals("error.msg.content.store", entity.getErrors().get(0).getUserMessageGlobalisationCode());
        assertEquals("/fineract-content: permission denied", entity.getErrors().get(0).getDefaultUserMessage());
    }

    @Test
    void processingFailureStaysAServerErrorButIsLabelled() {
        Response response = new ContentProcessorExceptionMapper()
                .toResponse(new ContentProcessorException("No image reader found for format"));

        assertEquals(500, response.getStatus());

        ApiGlobalErrorResponse entity = (ApiGlobalErrorResponse) response.getEntity();
        assertEquals(1, entity.getErrors().size());
        assertEquals("error.msg.content.processor", entity.getErrors().get(0).getUserMessageGlobalisationCode());
    }

    /**
     * The regression this whole change exists to prevent. {@code JerseyConfig.setup()} registers only beans annotated
     * {@code @Path} or {@code @Provider}, so a mapper that loses the annotation is silently never consulted and the
     * endpoint quietly goes back to returning a bare 500. Being a Spring {@code @Component} is not enough.
     */
    @ParameterizedTest
    @ValueSource(classes = { ContentPolicyExceptionMapper.class, ContentDetectorExceptionMapper.class, ContentStoreExceptionMapper.class,
            ContentProcessorExceptionMapper.class })
    void mapperIsAnnotatedProviderSoJerseyRegistersIt(Class<?> mapper) {
        assertTrue(mapper.isAnnotationPresent(Provider.class),
                mapper.getSimpleName() + " must be annotated @Provider or JerseyConfig will not register it");
    }
}
