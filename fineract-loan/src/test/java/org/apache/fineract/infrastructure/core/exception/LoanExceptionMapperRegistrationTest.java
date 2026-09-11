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
package org.apache.fineract.infrastructure.core.exception;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The sibling tests in this package already assert each mapper builds the right response. This one asserts the mappers
 * are actually reachable: {@code JerseyConfig.setup()} registers only beans annotated {@code @Path} or
 * {@code @Provider}, so a mapper that is merely a Spring {@code @Component} is silently never consulted and its
 * endpoint quietly goes back to returning a bare 500 — which is exactly the state these three were in.
 */
class LoanExceptionMapperRegistrationTest {

    @ParameterizedTest
    @ValueSource(classes = { LoanIdsHardLockedExceptionMapper.class, LinkedAccountRequiredExceptionMapper.class,
            MultiDisbursementDataRequiredExceptionMapper.class })
    void mapperIsAnnotatedProviderSoJerseyRegistersIt(Class<?> mapper) {
        assertTrue(mapper.isAnnotationPresent(Provider.class),
                mapper.getSimpleName() + " must be annotated @Provider or JerseyConfig will not register it");
    }
}
