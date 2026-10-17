package com.photospace.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileEmailSenderTest {

    @TempDir
    Path directory;

    @Test
    void writesOneEmlFilePerMessage() throws Exception {
        FileEmailSender sender = new FileEmailSender(directory.resolve("mail"), "Photo Space <no-reply@localhost>");

        sender.send(new EmailMessage("carol@example.com", "Join Zoë's Studio", "Line one\nLine two\n"));

        List<Path> files;
        try (Stream<Path> listing = Files.list(directory.resolve("mail"))) {
            files = listing.toList();
        }
        assertThat(files).singleElement().satisfies(file -> assertThat(file.toString()).endsWith(".eml"));
        String eml = Files.readString(files.getFirst(), StandardCharsets.UTF_8);
        assertThat(eml).startsWith("From: Photo Space <no-reply@localhost>\r\nTo: carol@example.com\r\n");
        assertThat(eml).contains("Subject: =?UTF-8?B?Sm9pbiBab8OrJ3MgU3R1ZGlv?=\r\n");
        assertThat(eml).contains("Content-Type: text/plain; charset=UTF-8\r\n");
        assertThat(eml).endsWith("\r\n\r\nLine one\r\nLine two\r\n");
    }

    @Test
    void messagesCannotAddHeaders() {
        EmailMessage message = new EmailMessage("a@example.com\r\nBcc: b@example.com", "Hi\nBcc: c@example.com", "secret link");

        assertThat(message.to()).doesNotContain("\n", "\r");
        assertThat(message.subject()).isEqualTo("Hi Bcc: c@example.com");
        assertThat(message.toString()).doesNotContain("example.com", "secret link");
    }
}
