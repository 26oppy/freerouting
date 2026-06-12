package app.freerouting.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.UUID;

public class BaseController {

  @Context
  private HttpHeaders httpHeaders;

  private final AuthService authService;

  public BaseController() {
      this.authService = null; 
  }

  @Inject
  public BaseController(AuthService authService) {
      this.authService = authService;
  }

  protected void setHttpHeaders(HttpHeaders httpHeaders) {
      this.httpHeaders = httpHeaders;
  }

  protected UUID authenticateUser() {
    String userIdString = httpHeaders.getHeaderString("Freerouting-Profile-ID");
    String userEmailString = httpHeaders.getHeaderString("Freerouting-Profile-Email");

    // BUG FIX 1: Use isBlank() instead of isEmpty() to catch whitespace-only headers
    boolean hasNoId = (userIdString == null || userIdString.isBlank());
    boolean hasNoEmail = (userEmailString == null || userEmailString.isBlank());

    if (hasNoId && hasNoEmail) {
      throw new IllegalArgumentException("Freerouting-Profile-ID or Freerouting-Profile-Email HTTP request header must be set in order to get authenticated.");
    }

    UUID userId = null;

    // 1. Attempt to parse the userId directly from the header
    if (!hasNoId) {
      try {
        // BUG FIX 2: Trim the string before parsing to prevent accidental whitespace failures
        userId = UUID.fromString(userIdString.trim());
      } catch (IllegalArgumentException e) {
        // We couldn't parse the userId (e.g., malformed), so we fall back to e-mail address
      }
    }

    // 2. Fallback: get userId from e-mail address
    if ((userId == null) && (!hasNoEmail)) {
      userId = authService.resolveUuidByEmail(userEmailString.trim());
    }

    if (userId == null) {
      throw new IllegalArgumentException("The user couldn't be authenticated based on the Freerouting-Profile-ID or Freerouting-Profile-Email HTTP request header values.");
    }

    // 3. Authenticate the user by calling the auth endpoint
    boolean isAuthenticated = authService.validateSession(userId);
    if (!isAuthenticated) {
      throw new SecurityException("User authentication failed at the external auth endpoint.");
    }

    return userId;
  }
}