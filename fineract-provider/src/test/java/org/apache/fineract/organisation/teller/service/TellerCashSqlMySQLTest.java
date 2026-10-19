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

import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.service.database.DatabaseType;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The teller SQL on MySQL, the dialect the original TIME() calls were written for - at the version upstream's MySQL
 * build runs (build-mysql.yml). Only a real MySQL shows its CAST(NULLIF(..) AS TIME) misreading "8:00" as 00:00:08.
 */
@Testcontainers(disabledWithoutDocker = true)
class TellerCashSqlMySQLTest extends TellerCashSqlTestBase {

    @Container
    private static final MySQLContainer<?> DATABASE = new MySQLContainer<>(DockerImageName.parse("mysql:9.7"));

    private static DataSource dataSource;

    @Override
    protected DataSource dataSource() {
        if (dataSource == null) {
            // One connection: PaginationHelper reads MySQL's FOUND_ROWS(), which only sees the same connection's query.
            dataSource = new SingleConnectionDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword(), true);
        }
        return dataSource;
    }

    @Override
    protected DatabaseType databaseType() {
        return DatabaseType.MYSQL;
    }
}
