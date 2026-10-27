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
package org.apache.fineract.portfolio.search.data;

import org.apache.fineract.portfolio.search.SearchConstants.SearchSupportedResources;

public class SearchConditions {

    private final String searchQuery;
    private final String searchResource;
    private final Boolean clientSearch;
    private final Boolean groupSearch;
    private final Boolean loanSeach;
    private final Boolean savingSeach;
    private final Boolean shareSeach;
    private final Boolean clientIdentifierSearch;
    private Boolean exactMatch;
    private final String hierarchy;
    // The kinds of row the group search (centers and groups) and the savings search (savings accounts, fixed and
    // recurring deposits) return. Each kind has its own read permission; all of them unless narrowed by withRowKinds.
    private final boolean centerRows;
    private final boolean groupRows;
    private final boolean savingsDepositRows;
    private final boolean fixedDepositRows;
    private final boolean recurringDepositRows;

    public SearchConditions(final String searchQueryParam, final String searchResource, Boolean exactMatch, final String hierarchy) {
        this.searchQuery = searchQueryParam;
        this.searchResource = searchResource;
        this.exactMatch = exactMatch;
        this.hierarchy = hierarchy;
        this.clientSearch = null == searchResource
                || searchResource.toLowerCase().contains(SearchSupportedResources.CLIENTS.name().toLowerCase());
        this.groupSearch = null == searchResource
                || searchResource.toLowerCase().contains(SearchSupportedResources.GROUPS.name().toLowerCase());
        this.loanSeach = null == searchResource
                || searchResource.toLowerCase().contains(SearchSupportedResources.LOANS.name().toLowerCase());
        this.savingSeach = null == searchResource
                || searchResource.toLowerCase().contains(SearchSupportedResources.SAVINGS.name().toLowerCase());
        this.shareSeach = null == searchResource
                || searchResource.toLowerCase().contains(SearchSupportedResources.SHARES.name().toLowerCase());
        this.clientIdentifierSearch = null == searchResource
                || searchResource.toLowerCase().contains(SearchSupportedResources.CLIENTIDENTIFIERS.name().toLowerCase());
        this.centerRows = true;
        this.groupRows = true;
        this.savingsDepositRows = true;
        this.fixedDepositRows = true;
        this.recurringDepositRows = true;
    }

    public SearchConditions(final String searchQueryParam, final String searchResource, final Boolean clientSearch,
            final Boolean groupSearch, final Boolean loanSeach, final Boolean savingSeach, final Boolean shareSeach,
            final Boolean clientIdentifierSearch, Boolean exactMatch, final String hierarchy) {
        this.searchQuery = searchQueryParam;
        this.searchResource = searchResource;
        this.clientSearch = clientSearch;
        this.groupSearch = groupSearch;
        this.loanSeach = loanSeach;
        this.savingSeach = savingSeach;
        this.shareSeach = shareSeach;
        this.clientIdentifierSearch = clientIdentifierSearch;
        this.exactMatch = exactMatch;
        this.hierarchy = hierarchy;
        this.centerRows = true;
        this.groupRows = true;
        this.savingsDepositRows = true;
        this.fixedDepositRows = true;
        this.recurringDepositRows = true;
    }

    private SearchConditions(final SearchConditions conditions, final boolean centerRows, final boolean groupRows,
            final boolean savingsDepositRows, final boolean fixedDepositRows, final boolean recurringDepositRows) {
        this.searchQuery = conditions.searchQuery;
        this.searchResource = conditions.searchResource;
        this.clientSearch = conditions.clientSearch;
        this.groupSearch = conditions.groupSearch;
        this.loanSeach = conditions.loanSeach;
        this.savingSeach = conditions.savingSeach;
        this.shareSeach = conditions.shareSeach;
        this.clientIdentifierSearch = conditions.clientIdentifierSearch;
        this.exactMatch = conditions.exactMatch;
        this.hierarchy = conditions.hierarchy;
        this.centerRows = centerRows;
        this.groupRows = groupRows;
        this.savingsDepositRows = savingsDepositRows;
        this.fixedDepositRows = fixedDepositRows;
        this.recurringDepositRows = recurringDepositRows;
    }

    /**
     * A copy whose group search returns only the given kinds of row (centers, groups) and whose savings search only the
     * given deposit types (savings accounts, fixed deposits, recurring deposits). A search left with no kind of row
     * returns none.
     */
    public SearchConditions withRowKinds(final boolean centerRows, final boolean groupRows, final boolean savingsDepositRows,
            final boolean fixedDepositRows, final boolean recurringDepositRows) {
        return new SearchConditions(this, centerRows, groupRows, savingsDepositRows, fixedDepositRows, recurringDepositRows);
    }

    public String getSearchQuery() {
        return this.searchQuery;
    }

    public String getSearchResource() {
        return this.searchResource;
    }

    public Boolean getExactMatch() {
        return this.exactMatch;
    }

    public Boolean isClientSearch() {
        return this.clientSearch;
    }

    public Boolean isGroupSearch() {
        return this.groupSearch;
    }

    public Boolean isLoanSeach() {
        return this.loanSeach;
    }

    public Boolean isSavingSeach() {
        return this.savingSeach;
    }

    public Boolean isShareSeach() {
        return this.shareSeach;
    }

    public Boolean isClientIdentifierSearch() {
        return this.clientIdentifierSearch;
    }

    public String getHierarchy() {
        return this.hierarchy;
    }

    public boolean isCenterRows() {
        return this.centerRows;
    }

    public boolean isGroupRows() {
        return this.groupRows;
    }

    public boolean isSavingsDepositRows() {
        return this.savingsDepositRows;
    }

    public boolean isFixedDepositRows() {
        return this.fixedDepositRows;
    }

    public boolean isRecurringDepositRows() {
        return this.recurringDepositRows;
    }

}
