/**
 * Copyright (C) 2000-2026 Vaadin Ltd
 *
 * This program is available under Vaadin Commercial License and Service Terms.
 *
 * See <https://vaadin.com/commercial-license-and-service-terms> for the full
 * license.
 */
package com.vaadin.observability.tests.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Base class for Observability Kit integration tests. Spins up a headless
 * Chrome driver (or connects to a hub when configured) and navigates to the
 * view under test before each test method.
 */
@Execution(ExecutionMode.SAME_THREAD)
public abstract class AbstractIT extends BrowserTestBase
        implements DriverSupplier {

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

    /**
     * Polls the Prometheus endpoint until {@code done} holds. The in-browser
     * collector flushes on a timer and on recovery, so its samples arrive
     * shortly after the browser produced them rather than synchronously.
     *
     * @param done
     *            the condition on the scrape body to wait for
     * @param what
     *            what was waited for, for the failure message
     * @param timeout
     *            how long to keep polling
     * @return the scrape body that satisfied {@code done}
     */
    protected String awaitPrometheus(Predicate<String> done, String what,
            Duration timeout) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            body = fetch("/actuator/prometheus");
            if (done.test(body)) {
                return body;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(
                "timed out waiting for " + what + " in:\n" + body);
    }

    /**
     * Fetches {@code path} from the running server and returns the body,
     * asserting a 200.
     */
    protected String fetch(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI
                .create(getRootURL() + path).toURL().openConnection();
        conn.setRequestMethod("GET");
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new AssertionError("GET " + path + " returned " + status);
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Returns the value of the first Prometheus sample line for {@code name},
     * optionally narrowed to one whose label set contains {@code label}, or
     * {@code -1.0} if absent.
     */
    protected static double prometheusValue(String prometheusBody, String name,
            String label) {
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(name)
                        + (label == null ? "(?:\\{[^}]*\\})?"
                                : "\\{[^}]*" + Pattern.quote(label)
                                        + "[^}]*\\}")
                        + "\\s+([0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)",
                Pattern.MULTILINE);
        Matcher m = pattern.matcher(prometheusBody);
        return m.find() ? Double.parseDouble(m.group(1)) : -1.0;
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
