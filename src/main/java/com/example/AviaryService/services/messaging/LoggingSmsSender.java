package com.example.AviaryService.services.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// A stand-in for sending text messages: it only logs what it would send. Real
// texting (Twilio) is waiting on carrier approval (A2P 10DLC). When that clears,
// replace this with a TwilioSmsSender. The rest of the app uses SmsSender, so
// nothing else changes.
@Service
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public void send(String toPhoneE164, String body) {
        log.info("[SMS stub] would send to {}: {}", toPhoneE164, body);
    }
}
