package com.photospace.identity;

import java.util.UUID;

/** A user as shown to that same user. The email is the latest snapshot from their identity provider. */
public record UserProfile(UUID id, String displayName, String email) {}
