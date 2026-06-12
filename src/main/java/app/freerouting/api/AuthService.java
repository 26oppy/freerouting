package app.freerouting.api;

import java.util.UUID;

/**
 * Service for communicating with the external Freerouting authentication provider.
 */
public interface AuthService {
    
    /**
     * Look up a user's UUID based on their email address.
     * * @param email The caller's email address.
     * @return The associated UUID, or null if the user cannot be found.
     */
    UUID resolveUuidByEmail(String email);

    /**
     * Validate the given UUID against the active auth endpoint.
     * * @param userId The UUID to validate.
     * @return True if the session/user is valid and authenticated, false otherwise.
     */
    boolean validateSession(UUID userId);
}