package app.freerouting.interactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.BoardObserverAdaptor;
import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RoutingJob;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TutorialBoardUiSmokeTest {

  private RoutingBoard board;

  @BeforeEach
  void setUp() throws IOException {
    InteractiveSettings.resetForTesting();

    HeadlessBoardManager manager = new HeadlessBoardManager(new RoutingJob());
    try (FileInputStream input = new FileInputStream(findTutorialBoardDsn())) {
      manager.loadFromSpecctraDsn(
          input,
          new BoardObserverAdaptor(),
          new ItemIdentificationNumberGenerator());
    }

    board = manager.get_routing_board();
    assertNotNull(board, "Tutorial board must load successfully");
    assertNull(manager.getInteractiveSettings(),
        "Headless manager must not expose InteractiveSettings");
  }

  @AfterEach
  void tearDown() {
    InteractiveSettings.resetForTesting();
  }

  @Test
  void tutorialBoard_initializesInteractiveSettingsForGuiFlow() {
    InteractiveSettings settings = InteractiveSettings.reset(board);
    assertNotNull(settings, "InteractiveSettings.reset(board) must return a settings instance");
    assertEquals(board.get_layer_count(), settings.get_layer_count(),
        "Layer count must match between board and interactive settings");

    for (int i = 0; i < settings.get_layer_count(); i++) {
      settings.manual_trace_half_width_arr[i] =
          board.rules.get_default_net_class().get_trace_half_width(i);
      assertTrue(settings.manual_trace_half_width_arr[i] > 0,
          "manual_trace_half_width_arr[" + i + "] must be positive");
    }
  }

  private static File findTutorialBoardDsn() throws FileNotFoundException {
    Path current = Path.of(".").toAbsolutePath();
    while (current != null) {
      File candidate = current.resolve("examples/tutorial_board/tutorial_board.dsn").toFile();
      if (candidate.exists()) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new FileNotFoundException("Cannot find examples/tutorial_board/tutorial_board.dsn");
  }
}
