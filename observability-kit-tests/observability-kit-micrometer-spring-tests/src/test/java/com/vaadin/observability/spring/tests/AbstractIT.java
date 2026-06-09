/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.spring.tests;

import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.net.PortProber;
import org.slf4j.LoggerFactory;

import com.vaadin.testbench.BrowserTestBase;
import com.vaadin.testbench.DriverSupplier;
import com.vaadin.testbench.IPAddress;
import com.vaadin.testbench.Parameters;
import com.vaadin.testbench.TestBench;

/**
 * Base class for the Spring Micrometer integration tests. Spins up a headless
 * Chrome driver (or connects to a hub when configured) and navigates to the
 * view under test before each test method.
 */
@Execution(ExecutionMode.SAME_THREAD)
abstract class AbstractIT extends BrowserTestBase implements DriverSupplier {

    static final int SERVER_PORT = Integer.getInteger("serverPort", 8080);

    static String hostName;

    static boolean isHub;

    @BeforeAll
    public static void setupClass() {
        String hubHost = Parameters.getHubHostname();
        isHub = hubHost != null && !hubHost.isEmpty();
        hostName = isHub ? IPAddress.findSiteLocalAddress() : "localhost";
    }

    @BeforeEach
    public void setup() {
        getDriver().get(getRootURL() + getTestPath());
    }

    /**
     * Gets the absolute path to the test, starting with a "/".
     *
     * @return the path to the test, appended to {@link #getRootURL()} for the
     *         full test URL.
     */
    protected abstract String getTestPath();

    /**
     * Returns the URL to the root of the server, e.g. "http://localhost:8080".
     *
     * @return the URL to the root
     */
    protected String getRootURL() {
        return "http://" + getDeploymentHostname() + ":" + getDeploymentPort();
    }

    /**
     * Used to determine what port the test is running on.
     *
     * @return the port the test is running on, by default 8080
     */
    protected int getDeploymentPort() {
        return SERVER_PORT;
    }

    /**
     * Used to determine what host the test is running on.
     *
     * @return the host name of the deployment
     */
    protected String getDeploymentHostname() {
        return hostName;
    }

    @Override
    public WebDriver createDriver() {
        if (!isJavaInDebugMode() && !isHub) {
            return createHeadlessChromeDriver();
        }
        // Let the super class create the driver (e.g. against a hub).
        return null;
    }

    private WebDriver createHeadlessChromeDriver() {
        for (int i = 0; i < 3; i++) {
            try {
                return tryCreateHeadlessChromeDriver();
            } catch (Exception e) {
                LoggerFactory.getLogger(getClass()).warn(
                        "Unable to create chromedriver on attempt " + i, e);
            }
        }
        throw new RuntimeException(
                "Gave up trying to create a chromedriver instance");
    }

    private static WebDriver tryCreateHeadlessChromeDriver() {
        ChromeOptions headlessOptions = createHeadlessChromeOptions();

        int port = PortProber.findFreePort();
        ChromeDriverService service = new ChromeDriverService.Builder()
                .usingPort(port).withSilent(true).build();
        ChromeDriver chromeDriver = new ChromeDriver(service, headlessOptions);
        return TestBench.createDriver(chromeDriver);
    }

    static ChromeOptions createHeadlessChromeOptions() {
        final ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless", "--disable-gpu");
        return options;
    }

    static boolean isJavaInDebugMode() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments()
                .toString().contains("jdwp");
    }
}
