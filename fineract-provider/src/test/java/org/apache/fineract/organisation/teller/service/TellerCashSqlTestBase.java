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
package org.apache.fineract.organisation.teller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.core.service.database.DatabaseType;
import org.apache.fineract.infrastructure.core.service.database.DatabaseTypeResolver;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSource;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.SqlValidator;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformService;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.organisation.staff.domain.Staff;
import org.apache.fineract.organisation.staff.service.StaffReadService;
import org.apache.fineract.organisation.teller.data.CashierTransactionData;
import org.apache.fineract.organisation.teller.data.CashierTransactionDataValidator;
import org.apache.fineract.organisation.teller.data.CashierTransactionsWithSummaryData;
import org.apache.fineract.organisation.teller.domain.Cashier;
import org.apache.fineract.organisation.teller.domain.Teller;
import org.apache.fineract.organisation.teller.exception.CashierAlreadyAllocated;
import org.apache.fineract.organisation.teller.exception.CashierInsufficientAmountException;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Runs the teller module's hand-written SQL - the till summary and the cashier-allocation checks - against a real
 * database, once per supported dialect (see the subclasses).
 *
 * Both defects this pins were invisible to every other test because nothing executed this SQL: the cashier checks
 * called MySQL's TIME(), a syntax error on PostgreSQL, and the till summary compared a timestamp with the allocation's
 * last DATE, so cash moved on that day dropped out of the till.
 *
 * The schema is the minimum these queries read, with the column types the Liquibase changelog gives a real tenant
 * (timestamp vs date matters to the window under test, and BIT(1) vs BOOLEAN to the full_day comparisons).
 */
abstract class TellerCashSqlTestBase {

    private static final String TENANT_ZONE = "Africa/Harare";
    private static final String USD = "USD";

    // One-day allocation, the ordinary daily shape: the whole till lives on this date.
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    private static final long OFFICE = 1L;
    private static final long STAFF = 7L;
    private static final long APP_USER = 70L;
    private static final long TELLER = 3L;
    private static final long CASHIER = 5L;
    private static final long CLIENT = 11L;
    private static final long SAVINGS = 21L;
    private static final long LOAN = 31L;

    private static final int SAVINGS_DEPOSIT = 1;
    private static final int LOAN_REPAYMENT = 2;
    private static final int CLIENT_PAY_CHARGE = 1;

    private static final List<String> TABLES = List.of("m_office", "m_staff", "m_appuser", "m_tellers", "m_cashiers",
            "m_cashier_transactions", "r_enum_value", "m_client", "m_savings_account", "m_savings_account_transaction", "m_loan",
            "m_loan_transaction", "m_client_transaction", "m_payment_detail", "m_payment_type", "m_account_transfer_transaction");

    private JdbcTemplate jdbc;
    private TellerManagementReadPlatformServiceImpl readService;
    private CashierTransactionDataValidator validator;
    private Staff staff;

    protected abstract DataSource dataSource();

    protected abstract DatabaseType databaseType();

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", TENANT_ZONE, null));

        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        createSchema();

        DatabaseTypeResolver resolver = mock(DatabaseTypeResolver.class);
        when(resolver.databaseType()).thenReturn(databaseType());
        when(resolver.isMySQL()).thenReturn(databaseType() == DatabaseType.MYSQL);
        when(resolver.isPostgreSQL()).thenReturn(databaseType() == DatabaseType.POSTGRESQL);
        DatabaseSpecificSQLGenerator sqlGenerator = new DatabaseSpecificSQLGenerator(resolver, mock(RoutingDataSource.class));
        CurrencyReadPlatformService currencies = mock(CurrencyReadPlatformService.class);
        when(currencies.retrieveAllowedCurrencies()).thenReturn(List.of());

        readService = new TellerManagementReadPlatformServiceImpl(jdbc, mock(PlatformSecurityContext.class),
                mock(OfficeReadPlatformService.class), mock(StaffReadService.class), currencies, sqlGenerator,
                new PaginationHelper(sqlGenerator, resolver), mock(SqlValidator.class));
        validator = new CashierTransactionDataValidator(readService, new NamedParameterJdbcTemplate(dataSource));

        staff = mock(Staff.class);
        when(staff.getId()).thenReturn(STAFF);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    // ---- till summary ----------------------------------------------------------------------------------------------

    @Test
    void cashMovedOnTheAllocationsLastDayIsInTheTill() {
        fullDayCashier(CASHIER, DAY, DAY);
        cashierTxn(101, 101, "1000", DAY.atTime(7, 30)); // allocate
        cashierTxn(102, 103, "50", DAY.atTime(12, 0)); // cash in
        cashierTxn(103, 102, "300", DAY.atTime(16, 45)); // settle
        savingsDeposit(201, "100", DAY);

        CashierTransactionsWithSummaryData summary = summary();

        // Before the fix the cash-in and the settle were dropped (created after midnight on end_date), so the till
        // read 1100 and a second settle of the same cash passed the overdraw guard.
        assertThat(summary.getSumCashAllocation()).isEqualByComparingTo("1000");
        assertThat(summary.getSumInwardCash()).isEqualByComparingTo("150");
        assertThat(summary.getSumCashSettlement()).isEqualByComparingTo("300");
        assertThat(summary.getNetCash()).isEqualByComparingTo("850");
    }

    @Test
    void customerCashDatedTheDayAfterTheAllocationIsNotInTheTill() {
        fullDayCashier(CASHIER, DAY, DAY);
        cashierTxn(101, 101, "1000", DAY.atTime(7, 30));
        savingsDeposit(201, "100", DAY);
        savingsDeposit(202, "999", DAY.plusDays(1));
        loanRepayment(301, "40", DAY.plusDays(1));
        clientCharge(401, "5", DAY.plusDays(1));

        CashierTransactionsWithSummaryData summary = summary();

        // Before the fix the loan and client windows ran to end_date + 1 on a DATE column, so the next day's cash
        // (often the same teller's next allocation) was counted here too.
        assertThat(summary.getSumInwardCash()).isEqualByComparingTo("100");
        assertThat(summary.getNetCash()).isEqualByComparingTo("1100");
    }

    @Test
    void transactionListingUsesTheSameWindowAsTheSummary() {
        fullDayCashier(CASHIER, DAY, DAY);
        cashierTxn(101, 101, "1000", DAY.atTime(7, 30));
        cashierTxn(103, 102, "300", DAY.atTime(16, 45));
        savingsDeposit(201, "100", DAY);
        savingsDeposit(202, "999", DAY.plusDays(1));
        loanRepayment(301, "40", DAY.plusDays(1));

        List<Long> listed = readService
                .retrieveCashierTransactions(CASHIER, false, null, null, USD, SearchParameters.builder().build()).getPageItems()
                .stream().map(CashierTransactionData::getId).sorted().collect(Collectors.toList());

        assertThat(listed).containsExactly(101L, 103L, 201L);
    }

    // ---- cashier allocation clash check ---------------------------------------------------------------------------

    @Test
    void partDayAllocationOverAFullDayOneIsRefused() {
        // Full-day rows store a single space as their times; casting that to TIME is an error on PostgreSQL.
        fullDayCashier(CASHIER, DAY, DAY.plusDays(30));

        assertThatThrownBy(() -> validator.validateCashierAllowedDateAndTime(partDay(DAY, "9:00", "12:00"), teller()))
                .isInstanceOf(CashierAlreadyAllocated.class);
    }

    @Test
    void partDayAllocationInsideALongerShiftIsRefused() {
        partDayCashier(CASHIER, DAY, DAY, "8:00", "17:00");

        // Neither end of the existing 08:00-17:00 shift falls inside 10:00-12:00; the old check only looked for that.
        assertThatThrownBy(() -> validator.validateCashierAllowedDateAndTime(partDay(DAY, "10:00", "12:00"), teller()))
                .isInstanceOf(CashierAlreadyAllocated.class);
    }

    @Test
    void partDayShiftsWhoseHoursDoNotOverlapAreAllowed() {
        partDayCashier(CASHIER, DAY, DAY, "8:00", "11:30");

        // "13:5" is how the allocation write path renders 13:05 (minutes are not zero-padded).
        assertThatCode(() -> validator.validateCashierAllowedDateAndTime(partDay(DAY, "13:5", "15:00"), teller()))
                .doesNotThrowAnyException();
    }

    @Test
    void shiftsThatOnlyTouchStillClash() {
        partDayCashier(CASHIER, DAY, DAY, "8:00", "12:00");

        // Carried over from the original BETWEEN check: a shared boundary minute counts as an overlap.
        assertThatThrownBy(() -> validator.validateCashierAllowedDateAndTime(partDay(DAY, "12:00", "16:00"), teller()))
                .isInstanceOf(CashierAlreadyAllocated.class);
    }

    // ---- cash loan disbursal check ---------------------------------------------------------------------------------

    @Test
    void cashLoanDisbursalChecksThePartDayTillCoveringNow() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of(TENANT_ZONE));
        Assumptions.assumeTrue(now.toLocalTime().isBefore(LocalTime.of(23, 59)), "the shift below ends at 23:59");
        partDayCashier(CASHIER, now.toLocalDate(), now.toLocalDate(), "0:00", "23:59");
        cashierTxn(101, 101, "100", now.toLocalDate().atStartOfDay());

        AppUser user = mock(AppUser.class);
        when(user.getStaff()).thenReturn(staff);

        // Throwing proves the part-day row matched and its till was read; on PostgreSQL the TIME() query used to fail
        // first, and every cash disbursal by a staff-linked user with it.
        assertThatThrownBy(() -> validator.validateOnLoanDisbursal(user, USD, new BigDecimal("500")))
                .isInstanceOf(CashierInsufficientAmountException.class);
        assertThatCode(() -> validator.validateOnLoanDisbursal(user, USD, new BigDecimal("60"))).doesNotThrowAnyException();
    }

    @Test
    void cashLoanDisbursalIgnoresATillThatDoesNotCoverToday() {
        LocalDate yesterday = LocalDate.now(ZoneId.of(TENANT_ZONE)).minusDays(1);
        partDayCashier(CASHIER, yesterday, yesterday, "0:00", "23:59");
        cashierTxn(101, 101, "100", yesterday.atStartOfDay());

        AppUser user = mock(AppUser.class);
        when(user.getStaff()).thenReturn(staff);

        assertThatCode(() -> validator.validateOnLoanDisbursal(user, USD, new BigDecimal("1000000"))).doesNotThrowAnyException();
    }

    // ---- fixtures --------------------------------------------------------------------------------------------------

    private CashierTransactionsWithSummaryData summary() {
        return readService.retrieveCashierTransactionsWithSummary(CASHIER, false, null, null, USD, SearchParameters.builder().build());
    }

    private Cashier partDay(LocalDate from, String startTime, String endTime) {
        return new Cashier().setStaff(staff).setStartDate(from).setEndDate(from).setIsFullDay(false).setStartTime(startTime)
                .setEndTime(endTime);
    }

    private Teller teller() {
        return new Teller().setStartDate(LocalDate.of(2020, 1, 1));
    }

    private void fullDayCashier(long id, LocalDate from, LocalDate to) {
        // The allocation write path stores " " for both times on a full-day allocation.
        jdbc.update("INSERT INTO m_cashiers (id, staff_id, teller_id, description, start_date, end_date, start_time, end_time, full_day)"
                + " VALUES (?, ?, ?, 'till', ?, ?, ' ', ' ', ?)", id, STAFF, TELLER, from, to, true);
    }

    private void partDayCashier(long id, LocalDate from, LocalDate to, String startTime, String endTime) {
        jdbc.update("INSERT INTO m_cashiers (id, staff_id, teller_id, description, start_date, end_date, start_time, end_time, full_day)"
                + " VALUES (?, ?, ?, 'till', ?, ?, ?, ?, ?)", id, STAFF, TELLER, from, to, startTime, endTime, false);
    }

    private void cashierTxn(long id, int type, String amount, LocalDateTime created) {
        jdbc.update("INSERT INTO m_cashier_transactions (id, cashier_id, txn_type, txn_amount, txn_date, created_date, currency_code)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)", id, CASHIER, type, new BigDecimal(amount), created.toLocalDate(), created, USD);
    }

    private void savingsDeposit(long id, String amount, LocalDate date) {
        jdbc.update("INSERT INTO m_savings_account_transaction (id, savings_account_id, transaction_type_enum, amount, transaction_date,"
                + " created_date, created_by, is_reversed) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, SAVINGS, SAVINGS_DEPOSIT,
                new BigDecimal(amount), date, date.atTime(10, 0), APP_USER, false);
    }

    private void loanRepayment(long id, String amount, LocalDate date) {
        jdbc.update("INSERT INTO m_loan_transaction (id, loan_id, transaction_type_enum, amount, transaction_date, created_date,"
                + " created_by, is_reversed) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, LOAN, LOAN_REPAYMENT, new BigDecimal(amount), date,
                date.atTime(10, 0), APP_USER, false);
    }

    private void clientCharge(long id, String amount, LocalDate date) {
        jdbc.update("INSERT INTO m_client_transaction (id, client_id, transaction_type_enum, amount, transaction_date, created_date,"
                + " created_by, is_reversed, currency_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, CLIENT, CLIENT_PAY_CHARGE,
                new BigDecimal(amount), date, date.atTime(10, 0), APP_USER, false, USD);
    }

    private void createSchema() {
        boolean mysql = databaseType() == DatabaseType.MYSQL;
        // Liquibase renders "datetime" as DATETIME(6) on MySQL/MariaDB and TIMESTAMP on PostgreSQL, and "boolean" as BIT(1)
        // on MySQL/MariaDB.
        String timestamp = mysql ? "DATETIME(6)" : "TIMESTAMP";
        String bool = mysql ? "BIT(1)" : "BOOLEAN";

        for (String table : TABLES.reversed()) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE m_office (id BIGINT PRIMARY KEY, name VARCHAR(100))");
        jdbc.execute("CREATE TABLE m_staff (id BIGINT PRIMARY KEY, display_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE m_appuser (id BIGINT PRIMARY KEY, staff_id BIGINT)");
        jdbc.execute("CREATE TABLE m_tellers (id BIGINT PRIMARY KEY, office_id BIGINT, name VARCHAR(50), description VARCHAR(100),"
                + " valid_from DATE, valid_to DATE, state SMALLINT, debit_account_id BIGINT, credit_account_id BIGINT)");
        jdbc.execute("CREATE TABLE m_cashiers (id BIGINT PRIMARY KEY, staff_id BIGINT, teller_id BIGINT, description VARCHAR(100),"
                + " start_date DATE, end_date DATE, start_time VARCHAR(10), end_time VARCHAR(10), full_day " + bool + ")");
        jdbc.execute("CREATE TABLE m_cashier_transactions (id BIGINT PRIMARY KEY, cashier_id BIGINT, txn_type SMALLINT,"
                + " txn_amount DECIMAL(19,6), txn_date DATE, created_date " + timestamp + ", entity_type VARCHAR(50), entity_id BIGINT,"
                + " txn_note VARCHAR(200), currency_code VARCHAR(3))");
        jdbc.execute("CREATE TABLE r_enum_value (enum_name VARCHAR(100), enum_id INT, enum_value VARCHAR(100))");
        jdbc.execute("CREATE TABLE m_client (id BIGINT PRIMARY KEY, office_id BIGINT, account_no VARCHAR(20), display_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE m_savings_account (id BIGINT PRIMARY KEY, account_no VARCHAR(20), client_id BIGINT,"
                + " currency_code VARCHAR(3))");
        jdbc.execute("CREATE TABLE m_savings_account_transaction (id BIGINT PRIMARY KEY, savings_account_id BIGINT,"
                + " transaction_type_enum SMALLINT, amount DECIMAL(19,6), transaction_date DATE, created_date " + timestamp + ","
                + " created_by BIGINT, is_reversed " + bool + ", payment_detail_id BIGINT)");
        jdbc.execute("CREATE TABLE m_loan (id BIGINT PRIMARY KEY, account_no VARCHAR(20), client_id BIGINT, currency_code VARCHAR(3))");
        jdbc.execute("CREATE TABLE m_loan_transaction (id BIGINT PRIMARY KEY, loan_id BIGINT, transaction_type_enum SMALLINT,"
                + " amount DECIMAL(19,6), transaction_date DATE, created_date " + timestamp + ", created_by BIGINT, is_reversed " + bool
                + ", payment_detail_id BIGINT)");
        jdbc.execute("CREATE TABLE m_client_transaction (id BIGINT PRIMARY KEY, client_id BIGINT, transaction_type_enum SMALLINT,"
                + " amount DECIMAL(19,6), transaction_date DATE, created_date " + timestamp + ", created_by BIGINT, is_reversed " + bool
                + ", payment_detail_id BIGINT, currency_code VARCHAR(3))");
        jdbc.execute("CREATE TABLE m_payment_detail (id BIGINT PRIMARY KEY, payment_type_id BIGINT)");
        jdbc.execute("CREATE TABLE m_payment_type (id BIGINT PRIMARY KEY, is_cash_payment " + bool + ")");
        jdbc.execute("CREATE TABLE m_account_transfer_transaction (id BIGINT PRIMARY KEY, from_savings_transaction_id BIGINT,"
                + " to_savings_transaction_id BIGINT, from_loan_transaction_id BIGINT, to_loan_transaction_id BIGINT)");

        jdbc.update("INSERT INTO m_office (id, name) VALUES (?, 'Head Office')", OFFICE);
        jdbc.update("INSERT INTO m_staff (id, display_name) VALUES (?, 'Till Operator')", STAFF);
        jdbc.update("INSERT INTO m_appuser (id, staff_id) VALUES (?, ?)", APP_USER, STAFF);
        jdbc.update("INSERT INTO m_tellers (id, office_id, name, valid_from, state) VALUES (?, ?, 'Till 1', ?, 300)", TELLER, OFFICE,
                LocalDate.of(2020, 1, 1));
        jdbc.update("INSERT INTO r_enum_value (enum_name, enum_id, enum_value) VALUES ('savings_transaction_type_enum', ?, 'deposit')",
                SAVINGS_DEPOSIT);
        jdbc.update("INSERT INTO r_enum_value (enum_name, enum_id, enum_value) VALUES ('loan_transaction_type_enum', ?, 'REPAYMENT')",
                LOAN_REPAYMENT);
        jdbc.update("INSERT INTO r_enum_value (enum_name, enum_id, enum_value) VALUES ('client_transaction_type_enum', ?, 'PAY_CHARGE')",
                CLIENT_PAY_CHARGE);
        jdbc.update("INSERT INTO m_client (id, office_id, account_no, display_name) VALUES (?, ?, '000000011', 'A Customer')", CLIENT,
                OFFICE);
        jdbc.update("INSERT INTO m_savings_account (id, account_no, client_id, currency_code) VALUES (?, '000000021', ?, ?)", SAVINGS,
                CLIENT, USD);
        jdbc.update("INSERT INTO m_loan (id, account_no, client_id, currency_code) VALUES (?, '000000031', ?, ?)", LOAN, CLIENT, USD);
    }
}
