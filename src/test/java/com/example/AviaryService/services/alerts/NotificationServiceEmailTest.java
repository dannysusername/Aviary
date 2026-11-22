package com.example.AviaryService.services.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.example.AviaryService.entity.Aircraft;
import com.example.AviaryService.entity.AlertRecipient;
import com.example.AviaryService.entity.User;
import com.example.AviaryService.repositories.AircraftRepository;
import com.example.AviaryService.repositories.AlertRecipientRepository;
import com.example.AviaryService.repositories.AlertSendLogRepository;
import com.example.AviaryService.services.dashboard.PdfExportService;
import com.example.AviaryService.services.messaging.EmailSender;
import com.example.AviaryService.services.messaging.SmsSender;

// Spec 013 US2: every alert email carries a text and an HTML version, each
// with that recipient's own stop link, and the PDF uses the owner's lead times.
class NotificationServiceEmailTest {

    @Test
    void sendsTextAndHtmlWithEachRecipientsOwnStopLink() {
        EmailSender email = mock(EmailSender.class);
        when(email.isConfigured()).thenReturn(true);
        AlertRecipientRepository recipients = mock(AlertRecipientRepository.class);
        AlertSendLogRepository sendLog = mock(AlertSendLogRepository.class);
        when(sendLog.existsByRecipientIdAndSentAtAfter(anyLong(), any())).thenReturn(false);
        PdfExportService pdf = mock(PdfExportService.class);
        when(pdf.generateFleetPdf(any(), any(), any())).thenReturn(new byte[] {1});
        AircraftRepository aircraft = mock(AircraftRepository.class);

        User owner = new User();
        Aircraft a = new Aircraft(owner);
        a.setTailNumber("N12SAS");
        a.setOperatorName("Jane Doe");
        when(aircraft.findByUserOrderByIdAsc(owner)).thenReturn(List.of(a));
        when(recipients.findByUserAndStatus(owner, AlertRecipient.Status.ACCEPTED.name()))
            .thenReturn(List.of(recipient(1, "a@example.com", "tokA"), recipient(2, "b@example.com", "tokB")));

        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(r);

        NotificationService service = new NotificationService(email, mock(SmsSender.class), recipients, sendLog,
            pdf, aircraft, new AlertEmailRenderer(engine), java.time.Clock.systemUTC(), "https://aviary.example", "123 Main St");

        LeadTimes lead = new LeadTimes(7, 5);
        AlertDigest digest = new AlertDigest(true, "N12SAS — 1 due soon", 0, 1, Instant.now(), false,
            List.of(new AlertDigest.AircraftSection("N12SAS", "", "1.0", "2.0",
                List.of(new AlertDigest.DueItem("ELT Battery", AlertLevel.DUE_SOON, "5 days left", false, 0.7)), 3, 4)),
            lead);

        assertEquals(2, service.sendDigest(owner, digest));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(2)).sendPdf(anyString(), eq("N12SAS — 1 due soon"), text.capture(), html.capture(),
            any(), anyString());
        assertTrue(text.getAllValues().get(0).contains("token=tokA"));
        assertTrue(text.getAllValues().get(1).contains("token=tokB"));
        assertNotNull(html.getAllValues().get(0));
        assertTrue(html.getAllValues().get(0).contains("N12SAS") && html.getAllValues().get(0).contains("token=tokA"));
        assertTrue(html.getAllValues().get(1).contains("token=tokB"));
        // One PDF for all recipients, built with the owner's lead times.
        verify(pdf, times(1)).generateFleetPdf(any(), any(), eq(lead));
    }

    private static AlertRecipient recipient(long id, String dest, String token) {
        AlertRecipient r = new AlertRecipient();
        r.setId(id);
        r.setChannelEnum(AlertRecipient.Channel.EMAIL);
        r.setStatusEnum(AlertRecipient.Status.ACCEPTED);
        r.setDestination(dest);
        r.setConfirmToken(token);
        return r;
    }
}
