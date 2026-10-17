package com.photospace.mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local development's mailbox: writes each message to {@code <directory>/<time>-<id>.eml}, which any mail
 * program opens, and sends nothing. The files hold invitation links, so the directory is git-ignored.
 */
class FileEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(FileEmailSender.class);
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private final Path directory;
    private final String from;

    FileEmailSender(Path directory, String from) {
        this.directory = directory;
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) throws EmailDeliveryException {
        Instant now = Instant.now();
        Path file = directory.resolve(FILE_TIME.format(now) + "-" + UUID.randomUUID() + ".eml");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, render(message, now), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EmailDeliveryException(e.getClass().getSimpleName(), false, e);
        }
        // The path only: the file itself holds the recipient and the link.
        log.info("Wrote email to {}", file.toAbsolutePath());
    }

    private String render(EmailMessage message, Instant now) {
        return "From: " + from + "\r\n"
                + "To: " + message.to() + "\r\n"
                + "Subject: " + encodedWord(message.subject()) + "\r\n"
                + "Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(now.atOffset(ZoneOffset.UTC)) + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n"
                + message.textBody().replace("\r\n", "\n").replace("\n", "\r\n");
    }

    /** RFC 2047 base64 encoding for a subject that is not plain ASCII. */
    private static String encodedWord(String text) {
        if (StandardCharsets.US_ASCII.newEncoder().canEncode(text)) {
            return text;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
