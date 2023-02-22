package com.vaadin.observability.test;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import com.vaadin.flow.component.html.testbench.H1Element;
import com.vaadin.testbench.TestBench;
import com.vaadin.testbench.TestBenchDriverProxy;
import com.vaadin.testbench.TestBenchTestCase;

public class MainViewIT extends TestBenchTestCase {

    protected WebDriver createHeadlessChromeDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        if (!Boolean.getBoolean("noHeadless")) {
            options.addArguments("--headless");
        }
        TestBenchDriverProxy driver = TestBench
                .createDriver(new ChromeDriver(options));
        return driver;
    }

    @BeforeEach
    public void navigateToView() {
        WebDriverManager.chromedriver().setup();
        setDriver(createHeadlessChromeDriver());
        getDriver().get("http://localhost:8080/");
    }

    @Test
    public void verifyInstrumentation() {
        waitUntil(driver -> $(H1Element.class).exists());
    }
}
