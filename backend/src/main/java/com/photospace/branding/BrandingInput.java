package com.photospace.branding;

import com.photospace.shared.Text;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.UUID;

/**
 * The editable branding fields. Colors are {@code #rrggbb} hex values, stored in lower case. Text is stripped and
 * blank optional fields become null. There is no field for CSS, HTML, or scripts.
 */
record BrandingInput(
        @Size(max = 100) String displayName,
        UUID logoAssetId,
        @NotNull @Pattern(regexp = "#[0-9a-f]{6}", message = "must be a hex color such as #1a2b3c") String primaryColor,
        @NotNull @Pattern(regexp = "#[0-9a-f]{6}", message = "must be a hex color such as #1a2b3c") String accentColor,
        @NotNull BrandFont font,
        @Email @Size(max = 320) String email,
        @Pattern(regexp = "(?=.*[0-9])[0-9+()\\-. /]{3,50}",
                message = "must be 3 to 50 characters: digits, spaces, and + ( ) - . /") String phone,
        @Size(max = 500) String description) {

    BrandingInput {
        displayName = Text.optional(displayName);
        primaryColor = color(primaryColor);
        accentColor = color(accentColor);
        email = Text.optional(email);
        phone = Text.optional(phone);
        description = Text.optional(description);
    }

    private static String color(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
