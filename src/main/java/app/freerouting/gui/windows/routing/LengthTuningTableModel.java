package app.freerouting.gui.windows.routing;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.state.CoordinateTransform;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetLengthConstraint;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.table.AbstractTableModel;

/** Table model managing single-net trace length constraints, status, and filtering. */
public class LengthTuningTableModel extends AbstractTableModel {

  private static final String[] COLUMN_NAMES = {
    "Net", "Class", "Current Length", "Target Length", "Tolerance (±)", "Status"
  };

  /** Value object holding net state for a table row. */
  public static class NetRow {
    public final Net net;
    public final String name;
    public final String className;
    public final double currentLengthMm;
    public final Double targetLengthMm;
    public final Double toleranceMm;
    public final String status;
    public final boolean isConstrained;
    public final boolean isUnsatisfied;

    public NetRow(
        Net net,
        double currentLengthMm,
        Double targetLengthMm,
        Double toleranceMm,
        String status,
        boolean isConstrained,
        boolean isUnsatisfied) {
      this.net = net;
      this.name = net != null ? net.name : "";
      this.className =
          (net != null && net.getNetClass() != null) ? net.getNetClass().getName() : "";
      this.currentLengthMm = currentLengthMm;
      this.targetLengthMm = targetLengthMm;
      this.toleranceMm = toleranceMm;
      this.status = status;
      this.isConstrained = isConstrained;
      this.isUnsatisfied = isUnsatisfied;
    }
  }

  private final List<NetRow> allRows = new ArrayList<>();
  private final List<NetRow> filteredRows = new ArrayList<>();

  private String filterText = "";
  private boolean showConstrainedOnly = false;
  private boolean showUnsatisfiedOnly = false;
  private String unitString = "mm";

  public LengthTuningTableModel() {}

  /** Populates table rows by querying all nets on the specified board using default transform. */
  public void refreshData(RoutingBoard board) {
    refreshData(board, null);
  }

  /** Populates table rows using the specified coordinate transform for user unit display. */
  public void refreshData(RoutingBoard board, CoordinateTransform coordinateTransform) {
    allRows.clear();
    CoordinateTransform ct =
        coordinateTransform != null ? coordinateTransform : resolveCoordinateTransform(board);
    this.unitString = ct != null && ct.userUnit != null ? ct.userUnit.toString() : "mm";

    if (board == null || board.rules == null || board.rules.nets == null) {
      applyFilter();
      return;
    }

    int netCount = board.rules.nets.maxNetNumber();
    for (int netNo = 1; netNo <= netCount; netNo++) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) {
        continue;
      }

      double currentLength = toUser(ct, net.getTraceLength());
      NetLengthConstraint constraint = net.getLengthConstraint();

      Double targetLength = null;
      Double tol = null;
      String status = "Unconstrained";
      boolean isConstrained = false;
      boolean isUnsatisfied = false;

      if (constraint != null && constraint.isConstrained()) {
        isConstrained = true;
        targetLength = toUser(ct, constraint.targetLength());
        if (constraint.hasMin() && constraint.hasMax()) {
          tol = toUser(ct, (constraint.maxLength() - constraint.minLength()) / 2.0);
        }

        if (currentLength <= 0.0) {
          status = "Unrouted";
          isUnsatisfied = true;
        } else if (constraint.isSatisfied(net.getTraceLength())) {
          status = "Satisfied";
        } else if (constraint.hasMin() && net.getTraceLength() < constraint.minLength()) {
          status = "Deficit";
          isUnsatisfied = true;
        } else {
          status = "Excess";
          isUnsatisfied = true;
        }
      } else if (currentLength <= 0.0) {
        status = "Unrouted";
      }

      allRows.add(
          new NetRow(net, currentLength, targetLength, tol, status, isConstrained, isUnsatisfied));
    }

    applyFilter();
  }

  /** Updates filtering parameters and refreshes the displayed table rows. */
  public void setFilter(String text, boolean constrainedOnly, boolean unsatisfiedOnly) {
    this.filterText = text != null ? text.trim().toLowerCase(Locale.US) : "";
    this.showConstrainedOnly = constrainedOnly;
    this.showUnsatisfiedOnly = unsatisfiedOnly;
    applyFilter();
  }

  private void applyFilter() {
    filteredRows.clear();
    for (NetRow row : allRows) {
      if (showConstrainedOnly && !row.isConstrained) {
        continue;
      }
      if (showUnsatisfiedOnly && !row.isUnsatisfied) {
        continue;
      }
      if (!filterText.isEmpty()) {
        boolean matchName = row.name.toLowerCase(Locale.US).contains(filterText);
        boolean matchClass = row.className.toLowerCase(Locale.US).contains(filterText);
        if (!matchName && !matchClass) {
          continue;
        }
      }
      filteredRows.add(row);
    }
    fireTableDataChanged();
  }

  public int getTotalNetCount() {
    return allRows.size();
  }

  public int getConstrainedCount() {
    int count = 0;
    for (NetRow row : allRows) {
      if (row.isConstrained) {
        count++;
      }
    }
    return count;
  }

  public int getUnsatisfiedCount() {
    int count = 0;
    for (NetRow row : allRows) {
      if (row.isUnsatisfied) {
        count++;
      }
    }
    return count;
  }

  public NetRow getRowAt(int rowIndex) {
    if (rowIndex >= 0 && rowIndex < filteredRows.size()) {
      return filteredRows.get(rowIndex);
    }
    return null;
  }

  public Net getNetAt(int rowIndex) {
    NetRow row = getRowAt(rowIndex);
    return row != null ? row.net : null;
  }

  @Override
  public int getRowCount() {
    return filteredRows.size();
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
    NetRow row = getRowAt(rowIndex);
    if (row == null) {
      return "";
    }
    return switch (columnIndex) {
      case 0 -> row.name;
      case 1 -> row.className;
      case 2 -> String.format(Locale.US, "%.2f %s", row.currentLengthMm, unitString);
      case 3 ->
          row.targetLengthMm != null
              ? String.format(Locale.US, "%.2f %s", row.targetLengthMm, unitString)
              : "—";
      case 4 ->
          row.toleranceMm != null
              ? String.format(Locale.US, "±%.2f %s", row.toleranceMm, unitString)
              : "—";
      case 5 -> row.status;
      default -> "";
    };
  }

  public static CoordinateTransform resolveCoordinateTransform(RoutingBoard board) {
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
