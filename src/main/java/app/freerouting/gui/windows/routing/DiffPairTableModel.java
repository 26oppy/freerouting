package app.freerouting.gui.windows.routing;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.DifferentialPairDetector;
import app.freerouting.rules.Net;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.swing.table.AbstractTableModel;

/** Table model managing differential pairs, skew tolerances, and automatic pair detection. */
public class DiffPairTableModel extends AbstractTableModel {

  private static final String[] COLUMN_NAMES = {
    "Pair Name", "Positive Net (+)", "Negative Net (-)", "Current Skew", "Max Skew", "Status"
  };

  /** Value object holding differential pair row state. */
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
      this.positiveNetName = (pair != null && pair.positiveNet() != null) ? pair.positiveNet().name : "";
      this.negativeNetName = (pair != null && pair.negativeNet() != null) ? pair.negativeNet().name : "";
      this.positiveLengthMm = posLenMm;
      this.negativeLengthMm = negLenMm;
      this.currentSkewMm = skewMm;
      this.maxSkewMm = maxSkewMm;
      this.status = status;
      this.isUnsatisfied = isUnsatisfied;
    }
  }

  private final List<PairRow> rows = new ArrayList<>();

  public DiffPairTableModel() {}

  /** Populates table rows from all registered differential pairs on the board. */
  public void refreshData(RoutingBoard board) {
    rows.clear();
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

      double pLenMm = pNet != null ? toMm(board, pNet.getTraceLength()) : 0.0;
      double nLenMm = nNet != null ? toMm(board, nNet.getTraceLength()) : 0.0;
      double skewMm = Math.abs(pLenMm - nLenMm);
      double maxSkewMm = pair.hasMaxSkew() ? toMm(board, pair.maxSkew()) : 0.0;

      String status = "Balanced";
      boolean unsatisfied = false;

      if (pLenMm <= 0.0 || nLenMm <= 0.0) {
        status = "Unrouted";
        unsatisfied = true;
      } else if (pair.hasMaxSkew() && skewMm > maxSkewMm) {
        status = "Skew Exceeded";
        unsatisfied = true;
      }

      rows.add(new PairRow(pair, pLenMm, nLenMm, skewMm, maxSkewMm, status, unsatisfied));
    }

    fireTableDataChanged();
  }

  /**
   * Scans all nets on the board to auto-detect complementary differential pair naming conventions
   * using {@link DifferentialPairDetector}.
   *
   * @param board the routing board
   * @return count of newly registered differential pairs
   */
  public int autoDetectPairs(RoutingBoard board) {
    if (board == null || board.rules == null || board.rules.differentialPairs == null) {
      return 0;
    }

    List<DifferentialPair> detected = DifferentialPairDetector.detect(board.rules);
    int added = 0;
    for (DifferentialPair pair : detected) {
      try {
        board.rules.differentialPairs.add(pair);
        added++;
      } catch (IllegalArgumentException e) {
        // Skip pairs already registered or overlapping
      }
    }

    if (added > 0) {
      refreshData(board);
    }
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
      case 3 -> String.format(Locale.US, "%.3f mm", row.currentSkewMm);
      case 4 -> row.maxSkewMm > 0 ? String.format(Locale.US, "%.3f mm", row.maxSkewMm) : "—";
      case 5 -> row.status;
      default -> "";
    };
  }

  private static double toMm(RoutingBoard board, double boardUnits) {
    if (board != null
        && board.communication != null
        && board.communication.coordinateTransform != null) {
      return board.communication.coordinateTransform.boardToDsn(boardUnits);
    }
    return boardUnits;
  }
}
