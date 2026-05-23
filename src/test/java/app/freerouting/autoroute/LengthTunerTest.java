package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.AngleRestriction;
import app.freerouting.board.FixedState;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.io.specctra.DsnReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.GlobalSettings;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LengthTunerTest {

  @BeforeEach
  void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
  }

  @Test
  void lengthTunerExtendsTraceOnFixtureBoardWithoutIntroducingDrcViolations() throws IOException {
    RoutingJob job = new RoutingJob();
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.lengthTuningEnabled = true;
    settings.lengthTuningDefaultAmplitudeUm = 100.0;
    settings.lengthTuningDefaultPitchUm = 100.0;
    job.routerSettings = settings;

    try (InputStream inputStream = openFixture("empty_board.dsn")) {
      DsnReadResult result = DsnReader.readBoard(inputStream, null, null, "empty_board.dsn");
      assertInstanceOf(DsnReadResult.Success.class, result);
      job.board = (app.freerouting.board.RoutingBoard) ((DsnReadResult.Success) result).board();
    }

    int netNo = job.board.rules.nets.add("LENGTH_TUNED_NET", 1, false).net_number;
    job.board.rules.set_trace_angle_restriction(AngleRestriction.NINETY_DEGREE);
    int layer = 0;
    int traceHalfWidth = Math.max(10, job.board.rules.get_default_net_class().get_trace_half_width(layer));
    int clearanceClass = job.board.rules.get_default_net_class().get_trace_clearance_class();

    int boardWidth = job.board.bounding_box.ur.x - job.board.bounding_box.ll.x;
    int boardHeight = job.board.bounding_box.ur.y - job.board.bounding_box.ll.y;
    IntPoint start = new IntPoint(job.board.bounding_box.ll.x + boardWidth / 4, job.board.bounding_box.ll.y + boardHeight / 2);
    IntPoint end = new IntPoint(job.board.bounding_box.ll.x + boardWidth / 2, job.board.bounding_box.ll.y + boardHeight / 2);
    job.board.insert_trace(new Point[] { start, end }, layer, traceHalfWidth, new int[] { netNo }, clearanceClass,
        FixedState.UNFIXED);

    var net = job.board.rules.nets.get(netNo);
    assertTrue(net != null);
    double lengthBefore = net.get_trace_length();
    net.get_class().set_target_trace_length(lengthBefore + 1000);
    net.get_class().set_length_tolerance(0);

    new LengthTuner(job).run();

    double lengthAfter = net.get_trace_length();
    assertTrue(lengthAfter > lengthBefore, "Expected length tuner to increase the net trace length.");
    var drc = new DesignRulesChecker(job.board, job.drcSettings);
    assertEquals(0, drc.getAllClearanceViolations().size(),
        "Length tuning must not introduce clearance violations on this fixture.");
  }

  private InputStream openFixture(String fileName) throws IOException {
    Path base = Path.of(".").toAbsolutePath();
    Path candidate = base.resolve("fixtures").resolve(fileName);
    while (!candidate.toFile().exists() && base.getParent() != null) {
      base = base.getParent();
      candidate = base.resolve("fixtures").resolve(fileName);
    }
    return new FileInputStream(candidate.toFile());
  }
}
