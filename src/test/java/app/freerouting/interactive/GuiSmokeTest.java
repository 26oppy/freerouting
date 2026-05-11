package app.freerouting.interactive;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import app.freerouting.Freerouting;
import app.freerouting.constants.Constants;
import app.freerouting.gui.GuiManager;
import app.freerouting.management.SessionManager;
import app.freerouting.settings.GlobalSettings;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gui")
class GuiSmokeTest {

  @BeforeEach
  void setUp() {
    assumeFalse(GraphicsEnvironment.isHeadless(),
        "GUI smoke tests require a display (run with Xvfb in CI)");
    InteractiveSettings.resetForTesting();
    Freerouting.globalSettings = new GlobalSettings();
    Freerouting.globalSettings.userProfileSettings.userId = UUID.randomUUID().toString();
    Freerouting.globalSettings.version = Constants.FREEROUTING_VERSION;
  }

  @AfterEach
  void tearDown() {
    for (Frame frame : Frame.getFrames()) {
      frame.dispose();
    }
    try {
      var guiSession = SessionManager.getInstance().getGuiSession();
      if (guiSession != null) {
        SessionManager.getInstance().removeSession(guiSession.id.toString());
      }
    } catch (IllegalArgumentException ignored) {
    }
    if (Freerouting.globalSettings != null) {
      Freerouting.globalSettings.guiSettings.isRunning = false;
    }
    InteractiveSettings.resetForTesting();
  }

  @Test
  void initializeGui_createsVisibleFrame() throws InterruptedException {
    boolean initialized = GuiManager.InitializeGUI(Freerouting.globalSettings);
    assertTrue(initialized, "GUI initialization should succeed with a display");

    Frame visibleFrame = waitForVisibleFrame(Duration.ofSeconds(10));
    assertNotNull(visibleFrame, "A visible GUI frame should appear after initialization");
  }

  private static Frame waitForVisibleFrame(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      for (Frame frame : Frame.getFrames()) {
        if (frame.isDisplayable() && frame.isShowing()) {
          return frame;
        }
      }
      Thread.sleep(50);
    }
    return null;
  }
}
