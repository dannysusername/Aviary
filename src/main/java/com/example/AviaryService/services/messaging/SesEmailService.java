package com.example.AviaryService.services.messaging;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Attachment;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

// Sends emails through Amazon SES. Only this file uses AWS's code.
//
// Setup: needs an AWS access key with SES permission, the region, and a
// from-email verified in SES (all from .env via aviary.ses.*). If any is
// blank, isConfigured() is false and sending throws, so check it first.
@Service
public class SesEmailService implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SesEmailService.class);

    private final SesV2Client ses;
    private final String from;
    private final boolean configured;

    public SesEmailService(
            @Value("${aviary.ses.access-key:}") String accessKey,
            @Value("${aviary.ses.secret-key:}") String secretKey,
            @Value("${aviary.ses.region:us-east-1}") String region,
            @Value("${aviary.ses.from-email:}") String fromEmail,
            @Value("${aviary.ses.from-name:Aviary Service}") String fromName) {
        this.from = fromName + " <" + fromEmail + ">";
        this.configured = !accessKey.isBlank() && !secretKey.isBlank() && !fromEmail.isBlank();
        this.ses = !configured ? null : SesV2Client.builder()
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .build();
        if (!configured) {
            log.warn("SES not configured (aviary.ses.* missing) -- email sending is disabled.");
        }
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        dispatch(toEmail, subject, body, List.of());
    }

    @Override
    public void sendPdf(String toEmail, String subject, String body, byte[] pdfBytes, String filename) {
        Attachment pdf = Attachment.builder()
            .rawContent(SdkBytes.fromByteArray(pdfBytes))
            .fileName(filename)
            .contentType("application/pdf")
            .contentDisposition("ATTACHMENT")
            // A PDF is binary; base64 is the only encoding that survives email intact.
            .contentTransferEncoding("BASE64")
            .build();
        dispatch(toEmail, subject, body, List.of(pdf));
    }

    private void dispatch(String toEmail, String subject, String body, List<Attachment> attachments) {
        if (!configured) {
            throw new IllegalStateException("SES is not configured; cannot send email.");
        }
        Message.Builder message = Message.builder()
            .subject(Content.builder().data(subject).build())
            .body(Body.builder().text(Content.builder().data(body).build()).build());
        if (!attachments.isEmpty()) {
            message.attachments(attachments);
        }
        try {
            ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(toEmail).build())
                .content(EmailContent.builder().simple(message.build()).build())
                .build());
            log.info("SES accepted message to {}", toEmail);
        } catch (SesV2Exception e) {
            throw new RuntimeException("SES rejected the message: " + e.awsErrorDetails().errorMessage(), e);
        }
    }
}
