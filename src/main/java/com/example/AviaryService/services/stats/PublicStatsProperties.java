package com.example.AviaryService.services.stats;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Settings for the public stats endpoint (aviary.public-stats.* in
// application.properties). The defaults here match that file, so the app and
// the tests (whose application.properties replaces the main one) behave the same.
@ConfigurationProperties(prefix = "aviary.public-stats")
public record PublicStatsProperties(
        // Sites whose pages may read the stats from a browser (CORS).
        @DefaultValue({"https://danielibarra.com", "https://www.danielibarra.com"}) List<String> allowedOrigins,
        // Signup dates are shown as a calendar date in this zone.
        @DefaultValue("America/New_York") ZoneId zone,
        // How long one snapshot is served before it's recomputed.
        @DefaultValue("PT15M") Duration ttl) {
}
