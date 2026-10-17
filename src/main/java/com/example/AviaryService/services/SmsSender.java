package com.example.AviaryService.services;

// The "send a text message" interface the rest of the app uses. Real texting
// (Twilio) is waiting on carrier approval (A2P 10DLC), which takes weeks.
// Until then LoggingSmsSender stands in; a TwilioSmsSender will replace it.
// See docs/ALERTS_SPEC.md.
public interface SmsSender {

    boolean isConfigured();

    void send(String toPhoneE164, String body);
}
