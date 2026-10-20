package app.freerouting.gui.windows.routing;

import app.freerouting.board.facade.RoutingBoard;
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

  public LengthTuningTableModel() {}

  /** Populates table rows by querying all nets on the specified board. */
  public void refreshData(RoutingBoard board) {
    allRows.clear();
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

      double currentMm = toMm(board, net.getTraceLength());
      NetLengthConstraint constraint = net.getLengthConstraint();

      Double targetMm = null;
      Double tolMm = null;
      String status = "Unconstrained";
      boolean isConstrained = false;
      boolean isUnsatisfied = false;

      if (constraint != null && constraint.isConstrained()) {
        isConstrained = true;
        targetMm = toMm(board, constraint.targetLength());
        if (constraint.hasMin() && constraint.hasMax()) {
          tolMm = toMm(board, (constraint.maxLength() - constraint.minLength()) / 2.0);
        }

        if (currentMm <= 0.0) {
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
      } else if (currentMm <= 0.0) {
        status = "Unrouted";
      }

      allRows.add(
          new NetRow(net, currentMm, targetMm, tolMm, status, isConstrained, isUnsatisfied));
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
      case 2 -> String.format(Locale.US, "%.2f mm", row.currentLengthMm);
      case 3 ->
          row.targetLengthMm != null
              ? String.format(Locale.US, "%.2f mm", row.targetLengthMm)
              : "—";
      case 4 ->
          row.toleranceMm != null ? String.format(Locale.US, "±%.2f mm", row.toleranceMm) : "—";
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
