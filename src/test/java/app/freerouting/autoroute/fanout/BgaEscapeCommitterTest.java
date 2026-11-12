package app.freerouting.autoroute.fanout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.fanout.BgaBufferMarshaller.MarshaledBgaData;
import app.freerouting.autoroute.fanout.BgaEscapeCommitter.CommitResult;
import app.freerouting.autoroute.fanout.GpuBgaEscapeSolver.PinEscapeProposal;
import app.freerouting.board.actions.ItemIdGenerator;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.Component;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Layer;
import app.freerouting.board.model.structure.LayerStructure;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.state.BoardObserverAdaptor;
import app.freerouting.board.state.Communication;
import app.freerouting.core.library.Package;
import app.freerouting.core.library.Packages;
import app.freerouting.core.library.Padstack;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.Circle;
import app.freerouting.geometry.planar.ConvexShape;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.PolylineShape;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.io.CoordinateTransform;
import app.freerouting.rules.BoardRules;
import app.freerouting.rules.ClearanceMatrix;
import app.freerouting.rules.ViaInfo;
import app.freerouting.rules.ViaRule;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BgaEscapeCommitterTest {

  private static RoutingBoard createTestBoard() {
    Layer layer1 = new Layer("Top", true);
    Layer layer2 = new Layer("Bottom", true);
    LayerStructure layerStructure = new LayerStructure(new Layer[] {layer1, layer2});

    ClearanceMatrix clearanceMatrix = ClearanceMatrix.getDefaultInstance(layerStructure, 10);
    BoardRules boardRules = new BoardRules(layerStructure, clearanceMatrix);
    boardRules.createDefaultNetClass();

    Communication communication =
        new Communication(
            Unit.UM,
            1,
            new Communication.SpecctraParserInfo("\"", null, null, null, null, false),
            new CoordinateTransform(1, 0, 0),
            new ItemIdGenerator(),
            new BoardObserverAdaptor());

    RoutingBoard board =
        new RoutingBoard(
            new IntBox(0, 0, 100_000, 100_000),
            layerStructure,
            new PolylineShape[] {TileShape.getInstance(0, 0, 100_000, 100_000)},
            0,
            boardRules,
            communication);

    board.library.padstacks = new app.freerouting.core.library.Padstacks(layerStructure);
    board.library.packages = new Packages(board.library.padstacks);
    board.rules.nets.add("SIG", 1, false);

    ConvexShape[] viaShapes =
        new ConvexShape[] {new Circle(Point.ZERO, 100), new Circle(Point.ZERO, 100)};
    Padstack viaPadstack = board.library.padstacks.add("DefaultViaPad", viaShapes, true, false);
    ViaInfo viaInfo = new ViaInfo("DefaultVia", viaPadstack, 0, false, boardRules);
    boardRules.viaInfos.add(viaInfo);
    ViaRule viaRule = new ViaRule("DefaultViaRule");
    viaRule.appendVia(viaInfo);
    boardRules.viaRules.add(viaRule);
    boardRules.getDefaultNetClass().setViaRule(viaRule);
    boardRules.getDefaultNetClass().setTraceHalfWidth(50);

    return board;
  }

  @Test
  void testAuthoritativeContinuousDrcCommitWithZeroViolations() {
    RoutingBoard board = createTestBoard();

    // 8x8 = 64 pin BGA on 1000 um pitch
    int pitch = 1000;
    Padstack pad =
        board.library.padstacks.add(
            new ConvexShape[] {new Circle(Point.ZERO, 150), new Circle(Point.ZERO, 150)});

    List<Package.Pin> pkgPins = new ArrayList<>();
    for (int r = 0; r < 8; r++) {
      for (int c = 0; c < 8; c++) {
        double lx = (c - 3.5) * pitch;
        double ly = (r - 3.5) * pitch;
        pkgPins.add(new Package.Pin("P" + (r * 8 + c), pad.id, new FloatPoint(lx, ly), 0));
      }
    }

    Package pkg = board.library.packages.add(pkgPins.toArray(new Package.Pin[0]));
    Component comp =
        board.components.addPrecise(
            "U1", new FloatPoint(50000, 50000), 0.0, true, pkg, pkg, false, null);

    List<Pin> boardPins = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      Pin p = board.insertPin(comp.id, i, new int[] {i + 1}, 1, FixedState.UNFIXED);
      boardPins.add(p);
    }

    RouterSettings settings = new RouterSettings();
    settings.fanout.gpuMinBgaPins = 64;

    BgaEscapeDescriptor desc =
        BgaFootprintDetector.analyzeComponent(board, comp, boardPins, settings);
    assertNotNull(desc);

    List<PinEscapeProposal> proposals;
    try (MarshaledBgaData data = BgaBufferMarshaller.marshal(board, desc, settings)) {
      proposals = GpuBgaEscapeSolver.executeDeterministicWavefront(board, desc, data);
    }
    assertNotNull(proposals);

    // Commit proposals with authoritative host DRC validation
    CommitResult result = BgaEscapeCommitter.commit(board, desc, proposals);
    assertTrue(result.pinsCommitted() > 0, "At least some pins must be successfully committed");
    assertEquals(result.pinsCommitted(), result.viasInserted());
    assertEquals(result.pinsCommitted(), result.tracesInserted());

    // Authoritative full design rules check: ZERO violations allowed
    List<?> violations =
        new ArrayList<>(new DesignRulesChecker(board, null).getAllClearanceViolations());
    assertEquals(0, violations.size(), "Committed escapes must have 0 DRC clearance violations");
  }
}
