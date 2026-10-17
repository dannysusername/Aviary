package com.example.AviaryService.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import com.example.AviaryService.services.alerts.AlertScheduler;
import com.example.AviaryService.services.flights.FlightSyncService;

// One-off entrypoint for Heroku Scheduler. The Eco web dyno sleeps after 30
// minutes idle, so the in-process @Scheduled sweeps in FlightSyncService and
// AlertScheduler don't fire reliably. Heroku Scheduler runs this instead,
// once an hour, on a short-lived one-off dyno:
//
//   java -jar build/libs/AviaryService-0.0.1-SNAPSHOT.jar \
//     --spring.main.web-application-type=none --app.run-sync=true
//
// The bean only exists when app.run-sync=true is passed as a command-line
// arg, so a normal web boot is untouched. Do NOT set app.run-sync as a
// Heroku config var -- that would make every web boot run the sweep and
// exit. It runs both sweeps once (each independently, so one failing does
// not skip the other), then exits the JVM so the one-off dyno stops and
// stops drawing from the Eco hour pool.
@Component
@ConditionalOnProperty(name = "app.run-sync", havingValue = "true")
public class SyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncRunner.class);

    private final FlightSyncService flightSyncService;
    private final AlertScheduler alertScheduler;
    private final ApplicationContext context;

    public SyncRunner(FlightSyncService flightSyncService, AlertScheduler alertScheduler,
            ApplicationContext context) {
        this.flightSyncService = flightSyncService;
        this.alertScheduler = alertScheduler;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int flightRc = runStep("AeroAPI flight-sync sweep", flightSyncService::syncDueSubscriptions);
        int alertRc = runStep("maintenance-alert sweep", alertScheduler::tick);
        int exitCode = (flightRc == 0 && alertRc == 0) ? 0 : 1;
        // Clean Spring shutdown (close the datasource pool, run @PreDestroy),
        // then stop the JVM so the one-off dyno exits.
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int runStep(String label, Runnable step) {
        try {
            log.info("run-sync: {} starting", label);
            step.run();
            log.info("run-sync: {} done", label);
            return 0;
        } catch (Exception e) {
            log.error("run-sync: {} failed", label, e);
            return 1;
        }
    }
}
