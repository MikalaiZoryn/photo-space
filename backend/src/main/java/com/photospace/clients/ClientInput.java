package com.photospace.clients;

import com.photospace.shared.Text;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The editable fields of a client. Text is stripped and blank optional fields become null before validation
 * runs. Any other property in the request body, such as an ID or a workspace ID, is ignored.
 */
record ClientInput(
        @NotBlank @Size(max = 200) String name,
        @Email @Size(max = 320) String email,
        @Pattern(regexp = "(?=.*[0-9])[0-9+()\\-. /]{3,50}",
                message = "must be 3 to 50 characters: digits, spaces, and + ( ) - . /") String phone,
        @Size(max = 10_000) String notes) {

    ClientInput {
        name = Text.strip(name);
        email = Text.optional(email);
        phone = Text.optional(phone);
        notes = Text.optional(notes);
    }
}
