package app.freerouting.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;

public class BaseControllerTest {

    private HttpHeaders mockHeaders;
    private AuthService mockAuthService;
    private TestBaseController controller; // Use the specific subclass type

    // Helper static class to expose the protected method
    private static class TestBaseController extends BaseController {
        public TestBaseController(AuthService authService) {
            super(authService);
        }
        public UUID testAuthenticate() {
            return this.authenticateUser();
        }
    }

    @BeforeEach
    void setUp() {
        mockHeaders = mock(HttpHeaders.class);
        mockAuthService = mock(AuthService.class);

        // Instantiate our test helper
        controller = new TestBaseController(mockAuthService);
        
        // Inject the JAX-RS context via our test setter
        controller.setHttpHeaders(mockHeaders);
    }

    @Test
    void authenticatesSuccessfullyWithValidProfileId() {
        UUID expectedId = UUID.randomUUID();
        when(mockHeaders.getHeaderString("Freerouting-Profile-ID")).thenReturn(expectedId.toString());
        when(mockAuthService.validateSession(expectedId)).thenReturn(true);

        UUID result = controller.testAuthenticate(); // No casting needed now!

        assertEquals(expectedId, result);
    }

    @Test
    void fallsBackToEmailWhenProfileIdIsMissing() {
        UUID expectedId = UUID.randomUUID();
        String email = "engineer@freerouting.app";
        
        when(mockHeaders.getHeaderString("Freerouting-Profile-ID")).thenReturn(null);
        when(mockHeaders.getHeaderString("Freerouting-Profile-Email")).thenReturn(email);
        when(mockAuthService.resolveUuidByEmail(email)).thenReturn(expectedId);
        when(mockAuthService.validateSession(expectedId)).thenReturn(true);

        UUID result = controller.testAuthenticate();

        assertEquals(expectedId, result);
    }

    @Test
    void throwsSecurityExceptionWhenValidationFails() {
        UUID expectedId = UUID.randomUUID();
        when(mockHeaders.getHeaderString("Freerouting-Profile-ID")).thenReturn(expectedId.toString());
        when(mockAuthService.validateSession(expectedId)).thenReturn(false);

        assertThrows(SecurityException.class, () -> {
            controller.testAuthenticate();
        });
    }

    @Test
    void throwsIllegalArgumentExceptionWhenBothHeadersMissing() {
        when(mockHeaders.getHeaderString("Freerouting-Profile-ID")).thenReturn(null);
        when(mockHeaders.getHeaderString("Freerouting-Profile-Email")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> {
            controller.testAuthenticate();
        });
    }
}