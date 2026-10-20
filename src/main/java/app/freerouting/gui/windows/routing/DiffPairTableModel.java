package app.freerouting.gui.windows.routing;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.state.CoordinateTransform;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.DifferentialPairDetector;
import app.freerouting.rules.MeanderPatternType;
import app.freerouting.rules.Net;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.swing.table.AbstractTableModel;

/** Table model managing differential pairs, skew limits, and routing status. */
public class DiffPairTableModel extends AbstractTableModel {

  private static final String[] COLUMN_NAMES = {
    "Pair Name", "Positive Net (+)", "Negative Net (-)", "Current Skew", "Max Skew", "Status"
  };

  /** Value object holding differential pair display row state. */
  public static class PairRow {
    public final DifferentialPair pair;
    public final String baseName;
    public final String positiveNetName;
    public final String negativeNetName;
    public final double positiveLengthMm;
    public final double negativeLengthMm;
    public final double currentSkewMm;
    public final double maxSkewMm;
    public final String status;
    public final boolean isUnsatisfied;

    public PairRow(
        DifferentialPair pair,
        double posLenMm,
        double negLenMm,
        double skewMm,
        double maxSkewMm,
        String status,
        boolean isUnsatisfied) {
      this.pair = pair;
      this.baseName = pair != null ? pair.baseName() : "";
      this.positiveNetName =
          (pair != null && pair.positiveNet() != null) ? pair.positiveNet().name : "";
      this.negativeNetName =
          (pair != null && pair.negativeNet() != null) ? pair.negativeNet().name : "";
      this.positiveLengthMm = posLenMm;
      this.negativeLengthMm = negLenMm;
      this.currentSkewMm = skewMm;
      this.maxSkewMm = maxSkewMm;
      this.status = status;
      this.isUnsatisfied = isUnsatisfied;
    }
  }

  private final List<PairRow> rows = new ArrayList<>();
  private String unitString = "mm";

  public DiffPairTableModel() {}

  /** Populates table rows from all registered differential pairs using default transform. */
  public void refreshData(RoutingBoard board) {
    refreshData(board, null);
  }

  /** Populates table rows using the specified coordinate transform for user unit display. */
  public void refreshData(RoutingBoard board, CoordinateTransform coordinateTransform) {
    rows.clear();
    CoordinateTransform ct =
        coordinateTransform != null ? coordinateTransform : resolveCoordinateTransform(board);
    this.unitString = ct != null && ct.userUnit != null ? ct.userUnit.toString() : "mm";

    if (board == null
        || board.rules == null
        || board.rules.differentialPairs == null
        || board.rules.differentialPairs.isEmpty()) {
      fireTableDataChanged();
      return;
    }

    Collection<DifferentialPair> pairs = board.rules.differentialPairs.all();
    for (DifferentialPair pair : pairs) {
      Net pNet = pair.positiveNet();
      Net nNet = pair.negativeNet();

      double pLen = pNet != null ? toUser(ct, pNet.getTraceLength()) : 0.0;
      double nLen = nNet != null ? toUser(ct, nNet.getTraceLength()) : 0.0;
      double skew = Math.abs(pLen - nLen);
      double maxSkew = pair.hasMaxSkew() ? toUser(ct, pair.maxSkew()) : 0.0;

      String status = "Balanced";
      boolean unsatisfied = false;

      if (pLen <= 0.0 || nLen <= 0.0) {
        status = "Unrouted";
        unsatisfied = true;
      } else if (pair.hasMaxSkew() && skew > maxSkew) {
        status = "Skew Exceeded";
        unsatisfied = true;
      } else if (!pair.hasMaxSkew()) {
        status = "Unconstrained";
      }

      rows.add(new PairRow(pair, pLen, nLen, skew, maxSkew, status, unsatisfied));
    }

    fireTableDataChanged();
  }

  /**
   * Scans all nets on the board to auto-detect complementary differential pair naming conventions
   * using {@link DifferentialPairDetector}, and assigns default skew tolerances and meander
   * geometry.
   *
   * @param board the routing board
   * @return count of newly registered or defaulted differential pairs
   */
  public int autoDetectPairs(RoutingBoard board) {
    if (board == null || board.rules == null || board.rules.differentialPairs == null) {
      return 0;
    }

    CoordinateTransform ct = resolveCoordinateTransform(board);
    double defaultSkewBoard = ct != null ? ct.userToBoard(0.10) : 1000.0;

    List<DifferentialPair> detected = DifferentialPairDetector.detect(board.rules);
    int added = 0;
    for (DifferentialPair pair : detected) {
      try {
        DifferentialPair constrainedPair =
            new DifferentialPair(
                pair.positiveNet(),
                pair.negativeNet(),
                pair.baseName(),
                pair.coupledSpacing() > 0 ? pair.coupledSpacing() : defaultSkewBoard * 4.0,
                defaultSkewBoard);
        board.rules.differentialPairs.add(constrainedPair);
        if (pair.positiveNet() != null && pair.positiveNet().getMeanderConstraint() == null) {
          pair.positiveNet()
              .setMeanderConstraint(
                  board.rules.getDefaultMeanderConstraint(MeanderPatternType.DIFF_PAIR_SKEW));
        }
        if (pair.negativeNet() != null && pair.negativeNet().getMeanderConstraint() == null) {
          pair.negativeNet()
              .setMeanderConstraint(
                  board.rules.getDefaultMeanderConstraint(MeanderPatternType.DIFF_PAIR_SKEW));
        }
        added++;
      } catch (IllegalArgumentException e) {
        // Skip pairs already registered or overlapping
      }
    }

    // Also populate default skew tolerance for any pre-existing unconstrained pairs
    for (DifferentialPair existing : board.rules.differentialPairs.all()) {
      if (!existing.hasMaxSkew()) {
        DifferentialPair updated =
            new DifferentialPair(
                existing.positiveNet(),
                existing.negativeNet(),
                existing.baseName(),
                existing.coupledSpacing() > 0 ? existing.coupledSpacing() : defaultSkewBoard * 4.0,
                defaultSkewBoard);
        board.rules.differentialPairs.add(updated);
        added++;
      }
    }

    refreshData(board, ct);
    return added;
  }

  public PairRow getRowAt(int rowIndex) {
    if (rowIndex >= 0 && rowIndex < rows.size()) {
      return rows.get(rowIndex);
    }
    return null;
  }

  public DifferentialPair getPairAt(int rowIndex) {
    PairRow row = getRowAt(rowIndex);
    return row != null ? row.pair : null;
  }

  @Override
  public int getRowCount() {
    return rows.size();
  }

  @Override
  public int getColumnCount() {
    return COLUMN_NAMES.length;
  }

  @Override
  public String getColumnName(int column) {
    return COLUMN_NAMES[column];
  }

  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    PairRow row = getRowAt(rowIndex);
    if (row == null) {
      return "";
    }
    return switch (columnIndex) {
      case 0 -> row.baseName;
      case 1 -> row.positiveNetName;
      case 2 -> row.negativeNetName;
      case 3 -> String.format(Locale.US, "%.3f %s", row.currentSkewMm, unitString);
      case 4 ->
          row.maxSkewMm > 0 ? String.format(Locale.US, "%.3f %s", row.maxSkewMm, unitString) : "—";
      case 5 -> row.status;
      default -> "";
    };
  }

  private static CoordinateTransform resolveCoordinateTransform(RoutingBoard board) {
    if (board != null && board.communication != null) {
      double unitFactor =
          board.communication.coordinateTransform != null
              ? board.communication.coordinateTransform.boardToDsn(1)
              : 1.0;
      Unit u = board.communication.unit != null ? board.communication.unit : Unit.MM;
      return new CoordinateTransform(1.0, u, unitFactor, u);
    }
    return null;
  }

  private static double toUser(CoordinateTransform ct, double boardUnits) {
    return ct != null ? ct.boardToUser(boardUnits) : boardUnits;
  }
}
