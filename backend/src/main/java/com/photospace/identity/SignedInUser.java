package com.photospace.identity;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

/**
 * The principal stored in the server-side session after sign-in. It holds only the internal user ID, not
 * provider tokens or profile data. {@link #getName()} feeds Spring Session's principal index.
 */
public record SignedInUser(UUID userId) implements Principal, Serializable {

    @Override
    public String getName() {
        return userId.toString();
    }
}
