package com.example.AviaryService.controllers;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.AviaryService.services.AlertRecipientService;

// The token-guarded links that go out in confirmation / alert emails. No login
// required (SecurityConfig permits /alerts/confirm, /alerts/decline,
// /alerts/unsubscribe) -- the opaque token in the query string is the auth.
//
// Clicking a link (GET) only shows a page with one button; the button POSTs
// to actually confirm/decline. Corporate mail scanners open every link in an
// email, so a GET that changed state would confirm or unsubscribe people
// without them ever clicking. These POSTs are exempt from CSRF in
// SecurityConfig -- the unguessable token already proves the request came
// from the email.
@RestController
@RequestMapping("/alerts")
public class AlertPublicController {

    private final AlertRecipientService recipientService;

    public AlertPublicController(AlertRecipientService recipientService) {
        this.recipientService = recipientService;
    }

    @GetMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String confirmPage(@RequestParam(required = false) String token) {
        return actionPage("Confirm maintenance alerts",
            "Click below to start receiving maintenance alerts.", "/alerts/confirm", token, "Yes, send me alerts");
    }

    @PostMapping(value = "/confirm", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String confirm(@RequestParam(required = false) String token) {
        boolean ok = token != null && recipientService.confirm(token);
        return ok
            ? page("You're confirmed", "You'll now receive maintenance alerts. You can unsubscribe anytime from a link in any alert email.")
            : page("Link not valid", "This confirmation link is invalid or has expired. Ask whoever added you to send a fresh one.");
    }

    @GetMapping(value = "/decline", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String declinePage(@RequestParam(required = false) String token) {
        return actionPage("Decline maintenance alerts",
            "Click below if you don't want these alerts.", "/alerts/decline", token, "No thanks");
    }

    @PostMapping(value = "/decline", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String decline(@RequestParam(required = false) String token) {
        boolean ok = token != null && recipientService.decline(token);
        return ok
            ? page("Declined", "No problem. You won't receive maintenance alerts.")
            : page("Link not valid", "This link is invalid or has already been used.");
    }

    @GetMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String unsubscribePage(@RequestParam(required = false) String token) {
        return actionPage("Unsubscribe",
            "Click below to stop receiving these maintenance alerts.", "/alerts/unsubscribe", token, "Unsubscribe");
    }

    @PostMapping(value = "/unsubscribe", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String unsubscribe(@RequestParam(required = false) String token) {
        boolean ok = token != null && recipientService.decline(token);
        return ok
            ? page("Unsubscribed", "You've been removed from these maintenance alerts.")
            : page("Link not valid", "This link is invalid or has already been used.");
    }

    private static String actionPage(String heading, String message, String action, String token, String button) {
        // Tokens are base64url; anything else can't be one of ours.
        if (token == null || !token.matches("[A-Za-z0-9_-]{1,64}")) {
            return page("Link not valid", "This link is invalid or has already been used.");
        }
        return page(heading, message
            + "</p><form method=\"post\" action=\"" + action + "\">"
            + "<input type=\"hidden\" name=\"token\" value=\"" + token + "\">"
            + "<button type=\"submit\">" + button + "</button></form><p>");
    }

    private static String page(String heading, String message) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>" + heading + "</title>"
            + "<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;"
            + "max-width:32rem;margin:4rem auto;padding:0 1.25rem;color:#1c2b36;line-height:1.5}"
            + "h1{font-size:1.35rem}button{font:inherit;font-weight:600;padding:.6rem 1.2rem;"
            + "border:0;border-radius:6px;background:#2C9B6F;color:#fff;cursor:pointer}"
            + "button:hover{background:#1E7A54}</style></head><body>"
            + "<h1>" + heading + "</h1><p>" + message + "</p></body></html>";
    }
}
