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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.search.data.AdHocQuerySearchRequest;
import org.apache.fineract.portfolio.search.data.AdHocSearchQueryData;
import org.apache.fineract.portfolio.search.data.SearchConditions;
import org.apache.fineract.portfolio.search.data.SearchData;
import org.apache.fineract.portfolio.search.service.SearchReadService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Component;

@Path("/v1/search")
@Component
@Tag(name = "Search API", description = "Search API allows to search scoped resources clients, loans and groups on specified fields.")
@RequiredArgsConstructor
@Produces({ MediaType.APPLICATION_JSON })
public class SearchApiResource {

    // Permission entity names (READ_<name>) of the resource types the search returns, as seeded in m_permission, each
    // the one its own read API checks: the group search also returns centers (CentersApiResource checks READ_CENTER),
    // and the savings search fixed and recurring deposits (READ_FIXEDDEPOSITACCOUNT, READ_RECURRINGDEPOSITACCOUNT).
    private static final String CLIENT = "CLIENT";
    private static final String CLIENT_IDENTIFIER = "CLIENTIDENTIFIER";
    private static final String GROUP = "GROUP";
    private static final String CENTER = "CENTER";
    private static final String LOAN = "LOAN";
    private static final String SAVINGS_ACCOUNT = "SAVINGSACCOUNT";
    private static final String FIXED_DEPOSIT_ACCOUNT = "FIXEDDEPOSITACCOUNT";
    private static final String RECURRING_DEPOSIT_ACCOUNT = "RECURRINGDEPOSITACCOUNT";
    private static final String SHARE_ACCOUNT = "SHAREACCOUNT";

    private final PlatformSecurityContext context;
    private final SearchReadService searchReadService;

    @GET
    @Path("/template")
    @Operation(summary = "Retrive Adhoc Search query template", description = """
            Mandatory Fields

            search?query=000000001
            """)
    public AdHocSearchQueryData retrieveAdHocSearchQueryTemplate() {

        return searchReadService.retrieveAdHocQueryTemplate();
    }

    @GET
    @Operation(summary = "Search Resources", description = """
            Example Requests:

            search?query=000000001


            search?query=Petra&resource=clients,groups


            search?query=Petra&resource=clients,groups&exactMatch=true""")
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SearchApiResourceSwagger.GetSearchResponse.class))))
    public List<SearchData> searchData(@QueryParam("query") @Parameter(description = "query") final String query,
            @QueryParam("resource") @Parameter(description = "resource") final String resource,
            @DefaultValue("false") @QueryParam("exactMatch") @Parameter(description = "exactMatch") Boolean exactMatch) {

        final AppUser currentUser = context.authenticatedUser();
        final String hierarchy = currentUser.getOffice().getHierarchy();
        final SearchConditions requested = new SearchConditions(query, resource, exactMatch, hierarchy);

        return searchReadService.retriveMatchingData(restrictToReadableResources(requested, currentUser));
    }

    @POST
    @Path("/advance")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Adhoc query search", description = """
            AdHocQuery search has more search options, it is a POST request, \
            it uses request body to send search parameters


            Mandatory fields: entities

            Optional fields: \
            loanStatus, loanProducts, offices, loanDateOption, loanFromDate, loanToDate,
            includeOutStandingAmountPercentage, outStandingAmountPercentageCondition,
            minOutStandingAmountPercentage and maxOutStandingAmountPercentage OR outStandingAmountPercentage,
            includeOutstandingAmount, outstandingAmountCondition,
            minOutstandingAmount and maxOutstandingAmount OR outstandingAmount""")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SearchApiResourceSwagger.PostAdhocQuerySearchRequest.class)))
    public List<AdHocSearchQueryData> advancedSearch(final AdHocQuerySearchRequest request) {
        // the ad-hoc search aggregates the loan portfolio (count and outstanding per office and loan product)
        context.authenticatedUser().validateHasReadPermission(LOAN);
        return searchReadService.retrieveAdHocQueryMatchingData(request);
    }

    /**
     * Drops every requested resource type the user cannot read, so the default all-types search keeps working for a
     * user holding only some READ_ permissions. Within the group and savings searches, each kind of row (center or
     * group; savings account, fixed or recurring deposit) is returned only to a user who can read it. A search none of
     * whose requested types is readable is refused.
     */
    private static SearchConditions restrictToReadableResources(final SearchConditions requested, final AppUser user) {
        final boolean clients = isRequested(requested.isClientSearch()) && canRead(user, CLIENT);
        final boolean groupsRequested = isRequested(requested.isGroupSearch());
        final boolean centerRows = groupsRequested && canRead(user, CENTER);
        final boolean groupRows = groupsRequested && canRead(user, GROUP);
        final boolean groups = centerRows || groupRows;
        final boolean loans = isRequested(requested.isLoanSeach()) && canRead(user, LOAN);
        final boolean savingsRequested = isRequested(requested.isSavingSeach());
        final boolean savingsDepositRows = savingsRequested && canRead(user, SAVINGS_ACCOUNT);
        final boolean fixedDepositRows = savingsRequested && canRead(user, FIXED_DEPOSIT_ACCOUNT);
        final boolean recurringDepositRows = savingsRequested && canRead(user, RECURRING_DEPOSIT_ACCOUNT);
        final boolean savings = savingsDepositRows || fixedDepositRows || recurringDepositRows;
        final boolean shares = isRequested(requested.isShareSeach()) && canRead(user, SHARE_ACCOUNT);
        final boolean clientIdentifiers = isRequested(requested.isClientIdentifierSearch()) && canRead(user, CLIENT_IDENTIFIER);

        final boolean anyReadable = clients || groups || loans || savings || shares || clientIdentifiers;
        if (!anyReadable && isAnyRequested(requested)) {
            throw new NoAuthorizationException("User has no authority to read any of the requested search resources");
        }

        return new SearchConditions(requested.getSearchQuery(), requested.getSearchResource(), clients, groups, loans, savings, shares,
                clientIdentifiers, requested.getExactMatch(), requested.getHierarchy())
                .withRowKinds(centerRows, groupRows, savingsDepositRows, fixedDepositRows, recurringDepositRows);
    }

    private static boolean isRequested(final Boolean flag) {
        return Boolean.TRUE.equals(flag);
    }

    private static boolean isAnyRequested(final SearchConditions requested) {
        return isRequested(requested.isClientSearch()) || isRequested(requested.isGroupSearch()) || isRequested(requested.isLoanSeach())
                || isRequested(requested.isSavingSeach()) || isRequested(requested.isShareSeach())
                || isRequested(requested.isClientIdentifierSearch());
    }

    // same rule as AppUser.validateHasReadPermission: ALL_FUNCTIONS, ALL_FUNCTIONS_READ or READ_<entity>
    private static boolean canRead(final AppUser user, final String permissionEntityName) {
        return !user.hasNotPermissionForAnyOf("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ", "READ_" + permissionEntityName);
    }
}
