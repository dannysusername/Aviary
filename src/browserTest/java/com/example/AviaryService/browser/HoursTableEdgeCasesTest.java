package com.example.AviaryService.browser;

import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.UserRepository;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Edge-case rendering tests for the "My Hours" block and the aircraft-info
 * table on the dashboard. Seeds a User row with edge-case values, loads
 * /dashboard, asserts the cells render the expected text and the page
 * produces no console errors.
 *
 * Add a new test here every time something about the My Hours UI is
 * fixed or extended.
 */
class HoursTableEdgeCasesTest extends BrowserTestBase {

    @Autowired
    UserRepository users;

    /** Hook the console-error listener BEFORE navigating. Returns the captured-errors list. */
    private List<String> attachConsoleErrorListener() {
        List<String> errors = new ArrayList<>();
        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) errors.add(msg.text());
        });
        return errors;
    }

    private void assertNoConsoleErrors(List<String> errors) {
        if (!errors.isEmpty()) throw new AssertionError("Console errors: " + errors);
    }

    @Test
    void freshUserShowsZeroHoursAndEmptyAircraftInfo() {
        // User with no edits — getHobbsHours/getTachHours default to 0.0,
        // aircraft info fields are null.
        String username = seedUser("password");
        List<String> errors = attachConsoleErrorListener();

        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Hours block always renders "Hobbs Time: 0.0" / "Tach Time: 0.0" for
        // a brand-new user because the getters coalesce null → 0.0.
        assertThat(page.locator("#current-hobbs-display")).containsText("Hobbs Time:");
        assertThat(page.locator("#current-hobbs-display")).containsText("0");
        assertThat(page.locator("#current-tach-display")).containsText("Tach Time:");
        assertThat(page.locator("#current-tach-display")).containsText("0");

        // Aircraft info inputs are empty for a fresh user.
        assertThat(page.locator("input[name='makeModel']")).hasValue("");
        assertThat(page.locator("input[name='tailNumber']")).hasValue("");
        assertThat(page.locator("input[name='ownerName']")).hasValue("");
        assertThat(page.locator("input[name='makeModelSN']")).hasValue("");

        assertNoConsoleErrors(errors);
    }

    @Test
    void decimalHoursRenderWithoutTruncation() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        u.setHobbsHours(1234.5);
        u.setTachHours(987.6);
        users.save(u);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // The display string is built by Thymeleaf string-concat which uses
        // Double.toString(); 1234.5 should appear verbatim.
        assertThat(page.locator("#current-hobbs-display")).containsText("1234.5");
        assertThat(page.locator("#current-tach-display")).containsText("987.6");

        // The "Edit" form input mirrors the same value.
        assertThat(page.locator("#current-hobbs")).hasValue("1234.5");
        assertThat(page.locator("#current-tach")).hasValue("987.6");

        assertNoConsoleErrors(errors);
    }

    @Test
    void veryLargeHoursValueRenders() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        u.setHobbsHours(99999.9);
        u.setTachHours(99999.9);
        users.save(u);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        assertThat(page.locator("#current-hobbs-display")).containsText("99999.9");
        assertThat(page.locator("#current-tach-display")).containsText("99999.9");

        assertNoConsoleErrors(errors);
    }

    @Test
    void aircraftInfoFieldsPopulateInputs() {
        String username = seedUser("password");
        User u = users.findByUsername(username);
        u.setMakeModel("Cessna 172");
        u.setTailNumber("N12345");
        u.setOwnerName("Daniel I.");
        u.setMakeModelSN("17280342");
        users.save(u);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        assertThat(page.locator("input[name='makeModel']")).hasValue("Cessna 172");
        assertThat(page.locator("input[name='tailNumber']")).hasValue("N12345");
        assertThat(page.locator("input[name='ownerName']")).hasValue("Daniel I.");
        assertThat(page.locator("input[name='makeModelSN']")).hasValue("17280342");

        assertNoConsoleErrors(errors);
    }

    @Test
    void specialCharsInAircraftInfoDoNotBreakRendering() {
        // Specifically check HTML-special chars are escaped, not interpreted.
        // If the template forgot th:value escaping somewhere, "<script>" would
        // break the input and surface as a console/parse error.
        String username = seedUser("password");
        User u = users.findByUsername(username);
        u.setMakeModel("Boeing & Sons \"Best\" <model>");
        u.setTailNumber("N-1'2\"3");
        users.save(u);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // Round-trips through HTML attribute escape; input.value should equal
        // the original string.
        assertThat(page.locator("input[name='makeModel']")).hasValue("Boeing & Sons \"Best\" <model>");
        assertThat(page.locator("input[name='tailNumber']")).hasValue("N-1'2\"3");

        assertNoConsoleErrors(errors);
    }

    @Test
    void zeroHoursRendersWithoutCollapsingToEmpty() {
        // Regression-shaped test: a zero value is a legitimate value and must
        // not render as blank.
        String username = seedUser("password");
        User u = users.findByUsername(username);
        u.setHobbsHours(0.0);
        u.setTachHours(0.0);
        users.save(u);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // "Hobbs Time: 0.0" must include the "0" — not "Hobbs Time: ".
        assertThat(page.locator("#current-hobbs-display")).containsText("Hobbs Time:");
        assertThat(page.locator("#current-hobbs-display")).containsText("0");

        assertNoConsoleErrors(errors);
    }

    @Test
    void manualSourceStampSetsHoursUpdatedDataAttributes() {
        // The hours-updated span exposes data-updated + data-source which
        // dashboard.js reads to render the "last updated" label.
        String username = seedUser("password");
        User u = users.findByUsername(username);
        u.setHobbsHours(50.0);
        u.setHobbsUpdatedAt(java.time.Instant.parse("2025-10-01T12:00:00Z"));
        u.setHobbsUpdatedSource("manual");
        users.save(u);

        List<String> errors = attachConsoleErrorListener();
        loginAs(username, "password");
        page.waitForLoadState(LoadState.NETWORKIDLE);

        assertThat(page.locator("#hobbs-updated"))
                .hasAttribute("data-source", "manual");
        assertThat(page.locator("#hobbs-updated"))
                .hasAttribute("data-updated", "2025-10-01T12:00:00Z");

        assertNoConsoleErrors(errors);
    }
}
