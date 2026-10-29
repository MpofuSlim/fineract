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
package org.apache.fineract.portfolio.search.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.portfolio.search.data.SearchConditions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchReadServiceImplTest {

    private SearchReadServiceImpl service;

    @BeforeEach
    void setUp() {
        DatabaseSpecificSQLGenerator sqlGenerator = mock(DatabaseSpecificSQLGenerator.class);
        when(sqlGenerator.limit(50, 0)).thenReturn("LIMIT 50");
        service = new SearchReadServiceImpl(null, null, null, sqlGenerator, null);
    }

    @Test
    void everyKindOfRowIsReturnedUnlessNarrowed() {
        String sql = service.searchSchema(new SearchConditions("Petra", null, false, ".1."));

        assertTrue(sql.contains("from m_group g"));
        assertTrue(sql.contains("from m_savings_account s"));
        assertFalse(sql.contains("level_id ="));
        assertFalse(sql.contains("level_id <>"));
        assertFalse(sql.contains("deposit_type_enum in"));
        assertFalse(sql.contains("deposit_type_enum not in"));
    }

    @Test
    void theGroupSearchIsNarrowedToCentersOrToGroups() {
        // a center is group level 1, as the entityType CASE of the group search says
        String centers = service.searchSchema(conditions().withRowKinds(true, false, true, true, true));
        assertTrue(centers.contains("where o.hierarchy like :hierarchy and g.level_id = 1 and (g.account_no like :search"), centers);

        String groups = service.searchSchema(conditions().withRowKinds(false, true, true, true, true));
        assertTrue(groups.contains("where o.hierarchy like :hierarchy and g.level_id <> 1 and (g.account_no like :search"), groups);

        String neither = service.searchSchema(conditions().withRowKinds(false, false, true, true, true));
        assertFalse(neither.contains("from m_group g"), neither);
    }

    @Test
    void theSavingsSearchIsNarrowedToTheReadableDepositTypes() {
        String savingsOnly = service.searchSchema(conditions().withRowKinds(true, true, true, false, false));
        assertTrue(savingsOnly.contains("o.hierarchy like :hierarchy) and s.deposit_type_enum not in (200, 300) and (s.account_no"),
                savingsOnly);

        String savingsAndFixed = service.searchSchema(conditions().withRowKinds(true, true, true, true, false));
        assertTrue(savingsAndFixed.contains("and s.deposit_type_enum not in (300) and"), savingsAndFixed);

        String fixedOnly = service.searchSchema(conditions().withRowKinds(true, true, false, true, false));
        assertTrue(fixedOnly.contains("and s.deposit_type_enum in (200) and"), fixedOnly);

        String termDeposits = service.searchSchema(conditions().withRowKinds(true, true, false, true, true));
        assertTrue(termDeposits.contains("and s.deposit_type_enum in (200, 300) and"), termDeposits);

        String none = service.searchSchema(conditions().withRowKinds(true, true, false, false, false));
        assertFalse(none.contains("from m_savings_account s"), none);
    }

    private static SearchConditions conditions() {
        return new SearchConditions("Petra", null, false, ".1.");
    }
}
