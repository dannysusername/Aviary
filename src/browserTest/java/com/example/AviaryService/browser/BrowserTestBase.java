package com.example.AviaryService.browser;

import com.example.AviaryService.entity.ServiceTimeline;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("browsertest")
public abstract class BrowserTestBase {

    // Shared across the whole test class — launching Chromium is the most
    // expensive step in a Playwright run, so we keep one Browser per JVM
    // and isolate state with fresh BrowserContexts per test.
    private static Playwright playwright;
    private static Browser browser;

    protected BrowserContext context;
    protected Page page;

    @LocalServerPort
    protected int port;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    private static final AtomicInteger USER_COUNTER = new AtomicInteger();

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void shutdownBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void openPage() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (context != null) context.close();
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Create a user directly in the DB with a known password. Returns the username. */
    protected String seedUser(String rawPassword) {
        String username = "browser_user_" + USER_COUNTER.incrementAndGet();
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
        return username;
    }

    /**
     * Last Done / Due Date are stored as separate date and hours columns.
     * These take the combined "2025-10-01 100.5" form the tests were written
     * with (either part optional) and split it.
     */
    protected static void setLastDone(ServiceTimeline t, String value) {
        t.setLastDoneDate(datePart(value));
        t.setLastDoneHours(hoursPart(value));
    }

    protected static void setDueDate(ServiceTimeline t, String value) {
        t.setDueDateDate(datePart(value));
        t.setDueDateHours(hoursPart(value));
    }

    private static String datePart(String value) {
        if (value == null) return null;
        for (String part : value.trim().split("\\s+")) {
            if (part.matches("\\d{4}-\\d{2}-\\d{2}")) return part;
        }
        return null;
    }

    private static String hoursPart(String value) {
        if (value == null) return null;
        for (String part : value.trim().split("\\s+")) {
            if (!part.isEmpty() && !part.matches("\\d{4}-\\d{2}-\\d{2}")) return part;
        }
        return null;
    }

    /** Drive the real /login form. Asserts redirect to /dashboard succeeded. */
    protected void loginAs(String username, String rawPassword) {
        page.navigate(baseUrl() + "/login");
        page.fill("input[name='username']", username);
        page.fill("input[name='password']", rawPassword);
        // Login form has two type=submit buttons (real + Google placeholder).
        // The first is the real login button.
        page.locator("button.login-button").click();
        page.waitForURL(baseUrl() + "/dashboard");
    }
}
