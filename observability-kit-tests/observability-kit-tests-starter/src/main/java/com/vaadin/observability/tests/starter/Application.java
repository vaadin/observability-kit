/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.starter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * The JDBC/H2 stack that backs {@link DatabaseView} is deliberately confined to
 * the {@code db-demo} Spring profile (see {@code DbDemoConfig}); Boot's
 * {@link DataSourceAutoConfiguration} is excluded so that, outside that
 * profile, no {@code DataSource} exists at all. This keeps the GraalVM native
 * image lean — it carries none of the DB demo, and the kit's DataSource proxy
 * is never engaged there — while the JVM integration tests activate the profile
 * to exercise {@code vaadin.db.fetch.rows} end to end.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
