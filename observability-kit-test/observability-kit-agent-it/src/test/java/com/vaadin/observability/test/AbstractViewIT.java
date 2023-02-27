package com.vaadin.observability.test;

import com.vaadin.testbench.IPAddress;
import com.vaadin.testbench.parallel.ParallelTest;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.File;

public abstract class AbstractViewIT extends ParallelTest {
    public static final int SERVER_PORT = Integer
            .parseInt(System.getProperty("serverPort", "8080"));

    static String hostName;
    static boolean isHub;

    @BeforeClass
    public static void setupClass() {
        String hubHost = System
                .getProperty("com.vaadin.testbench.Parameters.hubHostname");
        isHub = hubHost != null && !hubHost.isEmpty();
        if (isHub) {
            String driver = System.getProperty("webdriver.chrome.driver");
            if (driver == null || !new File(driver).exists()) {
                WebDriverManager.chromedriver().setup();
            }
        }
        hostName = isHub ? IPAddress.findSiteLocalAddress() : "localhost";
    }

    @Before
    public void setUp() {
        getDriver().get(getRootURL() + getTestPath());
    }

    /**
     * Gets the absolute path to the test, starting with a "/".
     *
     * @return he path to the test, appended to {@link #getRootURL()} for the
     *         full test URL.
     */
    protected abstract String getTestPath();

    /**
     * Returns the URL to the root of the server, e.g. "http://localhost:8888".
     *
     * @return the URL to the root
     */
    protected String getRootURL() {
        return "http://" + getDeploymentHostname() + ":" + getDeploymentPort();
    }

    /**
     * Used to determine what port the test is running on.
     *
     * @return The port the test is running on, by default 8080
     */
    protected int getDeploymentPort() {
        return SERVER_PORT;
    }

    /**
     * Used to determine what URL to initially open for the test.
     *
     * @return the host name of development server
     */
    protected String getDeploymentHostname() {
        return hostName;
    }
}
