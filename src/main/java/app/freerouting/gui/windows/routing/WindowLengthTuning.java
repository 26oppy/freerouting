package app.freerouting.gui.windows.routing;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.state.CoordinateTransform;
import app.freerouting.gui.board.BoardFrame;
import app.freerouting.gui.board.BoardSavableSubWindow;
import app.freerouting.gui.workspace.GuiBoardManager;
import app.freerouting.gui.workspace.WorkspaceSettings;
import app.freerouting.rules.CornerStyle;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.MeanderPatternType;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetLengthConstraint;
import app.freerouting.rules.NetMeanderConstraint;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Interactive master-detail studio subwindow for configuring post-route trace length tuning,
 * differential pair intra-pair skew tolerances, and serpentine meander geometry.
 *
 * <p>Features a dynamic 2D vector preview panel ({@link MeanderDiagramPanel}), tabular net and pair
 * management, automatic pair detection, bus matching ("Match to Longest"), and immediate on-demand
 * tuning execution.
 */
public class WindowLengthTuning extends BoardSavableSubWindow {

  private static final DecimalFormat FMT =
      new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));

  private final BoardFrame boardFrame;
  private final LengthTuningTableModel singleNetTableModel = new LengthTuningTableModel();
  private final DiffPairTableModel diffPairTableModel = new DiffPairTableModel();

  private final JTable singleNetTable = new JTable(singleNetTableModel);
  private final JTable diffPairTable = new JTable(diffPairTableModel);
  private final MeanderDiagramPanel diagramPanel = new MeanderDiagramPanel();

  private final JTextField filterField = new JTextField(15);
  private final JCheckBox showConstrainedCheckBox = new JCheckBox();
  private final JCheckBox showUnsatisfiedCheckBox = new JCheckBox();
  private final JLabel activeUnitBadge = new JLabel("Unit: mm");

  // Inspector - Targets
  private final JCheckBox enableTargetConstraintBox = new JCheckBox();
  private final JTextField targetLengthField = new JTextField(7);
  private final JTextField toleranceField = new JTextField(7);
  private final JTextField maxSkewField = new JTextField(7);

  private final JLabel targetLengthUnitLabel = new JLabel("mm");
  private final JLabel toleranceUnitLabel = new JLabel("mm");
  private final JLabel maxSkewUnitLabel = new JLabel("mm");

  // Inspector - Meander Geometry
  private final JTextField minAmpField = new JTextField(6);
  private final JTextField maxAmpField = new JTextField(6);
  private final JTextField spacingField = new JTextField(6);
  private final JComboBox<String> cornerStyleCombo =
      new JComboBox<>(new String[] {"FILLET", "CHAMFER"});
  private final JTextField radiusField = new JTextField("80.0", 5);
  private final JCheckBox singleSidedCheckBox = new JCheckBox();

  private final JLabel minAmpUnitLabel = new JLabel("mm");
  private final JLabel maxAmpUnitLabel = new JLabel("mm");
  private final JLabel spacingUnitLabel = new JLabel("mm");

  private final JLabel statusSummaryLabel = new JLabel(" ");
  private final JTabbedPane tabbedPane = new JTabbedPane();

  /** Creates a new instance of WindowLengthTuning. */
  public WindowLengthTuning(BoardFrame boardFrame) {
    this.boardFrame = boardFrame;
    setLanguage(boardFrame.getLocale());

    this.setTitle(tm.getText("title"));
    this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    this.setPreferredSize(new Dimension(880, 600));
    this.setMinimumSize(new Dimension(720, 450));

    JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
    mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    // North Panel: Search & Filters
    mainPanel.add(createFilterPanel(), BorderLayout.NORTH);

    // Center Panel: Master-Detail Split
    mainPanel.add(createCenterSplitPane(), BorderLayout.CENTER);

    // South Panel: Status Summary & Primary Actions
    mainPanel.add(createBottomActionPanel(), BorderLayout.SOUTH);

    getContentPane().add(mainPanel);
    pack();

    setupListeners();
    refresh();
  }

  private JPanel createFilterPanel() {
    JPanel panel = new JPanel(new BorderLayout());

    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    JLabel filterLabel = new JLabel(tm.getText("filter"));
    left.add(filterLabel);
    filterField.setToolTipText(tm.getText("filter_tooltip"));
    left.add(filterField);

    showConstrainedCheckBox.setText(tm.getText("show_only_constrained"));
    showUnsatisfiedCheckBox.setText(tm.getText("show_only_unsatisfied"));
    left.add(showConstrainedCheckBox);
    left.add(showUnsatisfiedCheckBox);
    panel.add(left, BorderLayout.WEST);

    JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
    activeUnitBadge.setFont(activeUnitBadge.getFont().deriveFont(Font.BOLD));
    activeUnitBadge.setForeground(new Color(60, 90, 140));
    right.add(activeUnitBadge);
    panel.add(right, BorderLayout.EAST);

    return panel;
  }

  private JSplitPane createCenterSplitPane() {
    // -----------------------------------------------------------------------
    // Left: Tab 1 = Single Nets (with bus matching & zoom)
    // -----------------------------------------------------------------------
    singleNetTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    singleNetTable.setAutoCreateRowSorter(true);
    JPanel singleNetPanel = new JPanel(new BorderLayout());
    singleNetPanel.add(new JScrollPane(singleNetTable), BorderLayout.CENTER);

    JPanel singleNetTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    JButton matchLongestBtn = new JButton(tm.getText("btn_match_longest"));
    matchLongestBtn.setToolTipText(
        "Sets target length of all selected nets to the longest selected trace");
    matchLongestBtn.addActionListener(_ -> matchSelectedToLongest());
    singleNetTools.add(matchLongestBtn);

    JButton zoomNetBtn = new JButton(tm.getText("btn_zoom"));
    zoomNetBtn.addActionListener(_ -> zoomToSelectedNets());
    singleNetTools.add(zoomNetBtn);
    singleNetPanel.add(singleNetTools, BorderLayout.SOUTH);

    // -----------------------------------------------------------------------
    // Left: Tab 2 = Differential Pairs (with auto-detect, remove, zoom)
    // -----------------------------------------------------------------------
    diffPairTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    diffPairTable.setAutoCreateRowSorter(true);
    JPanel diffPairPanel = new JPanel(new BorderLayout());
    diffPairPanel.add(new JScrollPane(diffPairTable), BorderLayout.CENTER);

    JPanel diffPairTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    JButton autoDetectBtn = new JButton(tm.getText("auto_detect_pairs"));
    autoDetectBtn.setToolTipText(tm.getText("auto_detect_pairs_tooltip"));
    autoDetectBtn.addActionListener(_ -> autoDetectDifferentialPairs());
    diffPairTools.add(autoDetectBtn);

    JButton removePairBtn = new JButton(tm.getText("remove_pair"));
    removePairBtn.addActionListener(_ -> removeSelectedDifferentialPairs());
    diffPairTools.add(removePairBtn);

    JButton zoomPairBtn = new JButton(tm.getText("btn_zoom"));
    zoomPairBtn.addActionListener(_ -> zoomToSelectedNets());
    diffPairTools.add(zoomPairBtn);
    diffPairPanel.add(diffPairTools, BorderLayout.SOUTH);

    tabbedPane.addTab(tm.getText("tab_single_nets"), singleNetPanel);
    tabbedPane.addTab(tm.getText("tab_diff_pairs"), diffPairPanel);

    // -----------------------------------------------------------------------
    // Right: Inspector Controls & Vector Diagram
    // -----------------------------------------------------------------------
    JPanel inspectorPanel = new JPanel();
    inspectorPanel.setLayout(new BoxLayout(inspectorPanel, BoxLayout.Y_AXIS));
    inspectorPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));

    inspectorPanel.add(createTargetsSection());
    inspectorPanel.add(Box.createVerticalStrut(6));
    inspectorPanel.add(createMeanderGeometrySection());
    inspectorPanel.add(Box.createVerticalStrut(6));

    // Apply button right inside the inspector
    JButton applyBtn = new JButton(tm.getText("btn_apply"));
    applyBtn.setFont(applyBtn.getFont().deriveFont(Font.BOLD));
    applyBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
    applyBtn.addActionListener(_ -> applyRuleToSelected());
    inspectorPanel.add(applyBtn);
    inspectorPanel.add(Box.createVerticalStrut(8));

    JLabel previewLabel = new JLabel(tm.getText("label_preview"));
    previewLabel.setFont(previewLabel.getFont().deriveFont(Font.BOLD));
    previewLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    inspectorPanel.add(previewLabel);
    inspectorPanel.add(Box.createVerticalStrut(4));
    diagramPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
    inspectorPanel.add(diagramPanel);

    JSplitPane splitPane =
        new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabbedPane, new JScrollPane(inspectorPanel));
    splitPane.setResizeWeight(0.55);
    splitPane.setDividerLocation(480);
    return splitPane;
  }

  private JPanel createTargetsSection() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Length & Skew Targets"));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(2, 4, 2, 4);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;

    // Enable / Disable Constraint Checkbox
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.gridwidth = 3;
    enableTargetConstraintBox.setText(tm.getText("enable_length_constraint"));
    enableTargetConstraintBox.setSelected(true);
    enableTargetConstraintBox.addActionListener(_ -> onTargetEnableToggled());
    panel.add(enableTargetConstraintBox, gbc);
    gbc.gridwidth = 1;

    // Target Length
    gbc.gridx = 0;
    gbc.gridy = 1;
    panel.add(new JLabel(tm.getText("target_length")), gbc);
    gbc.gridx = 1;
    panel.add(targetLengthField, gbc);
    gbc.gridx = 2;
    panel.add(targetLengthUnitLabel, gbc);

    // Tolerance
    gbc.gridx = 0;
    gbc.gridy = 2;
    panel.add(new JLabel(tm.getText("tolerance")), gbc);
    gbc.gridx = 1;
    panel.add(toleranceField, gbc);
    gbc.gridx = 2;
    panel.add(toleranceUnitLabel, gbc);

    // Max Skew
    gbc.gridx = 0;
    gbc.gridy = 3;
    panel.add(new JLabel(tm.getText("max_skew")), gbc);
    gbc.gridx = 1;
    panel.add(maxSkewField, gbc);
    gbc.gridx = 2;
    panel.add(maxSkewUnitLabel, gbc);

    return panel;
  }

  private JPanel createMeanderGeometrySection() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Meander Geometry"));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(2, 4, 2, 4);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;

    // Min Amplitude
    gbc.gridx = 0;
    gbc.gridy = 0;
    panel.add(new JLabel(tm.getText("min_amplitude")), gbc);
    gbc.gridx = 1;
    panel.add(minAmpField, gbc);
    gbc.gridx = 2;
    panel.add(minAmpUnitLabel, gbc);

    // Max Amplitude
    gbc.gridx = 0;
    gbc.gridy = 1;
    panel.add(new JLabel(tm.getText("max_amplitude")), gbc);
    gbc.gridx = 1;
    panel.add(maxAmpField, gbc);
    gbc.gridx = 2;
    panel.add(maxAmpUnitLabel, gbc);

    // Trace Spacing
    gbc.gridx = 0;
    gbc.gridy = 2;
    panel.add(new JLabel(tm.getText("spacing")), gbc);
    gbc.gridx = 1;
    panel.add(spacingField, gbc);
    gbc.gridx = 2;
    panel.add(spacingUnitLabel, gbc);

    // Corner Style (Fillet vs Chamfer)
    gbc.gridx = 0;
    gbc.gridy = 3;
    panel.add(new JLabel(tm.getText("corner_style")), gbc);
    gbc.gridx = 1;
    gbc.gridwidth = 2;
    panel.add(cornerStyleCombo, gbc);
    gbc.gridwidth = 1;

    // Radius / Bevel Percentage (Direct Text Input)
    gbc.gridx = 0;
    gbc.gridy = 4;
    panel.add(new JLabel(tm.getText("radius_percentage")), gbc);
    gbc.gridx = 1;
    panel.add(radiusField, gbc);
    gbc.gridx = 2;
    panel.add(new JLabel("%"), gbc);

    // Meander Side
    gbc.gridx = 0;
    gbc.gridy = 5;
    panel.add(new JLabel(tm.getText("side")), gbc);
    gbc.gridx = 1;
    gbc.gridwidth = 2;
    singleSidedCheckBox.setText(tm.getText("side_single"));
    panel.add(singleSidedCheckBox, gbc);

    return panel;
  }

  private JPanel createBottomActionPanel() {
    JPanel panel = new JPanel(new BorderLayout());

    // Left: Status summary
    JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
    statusSummaryLabel.setForeground(new Color(80, 85, 95));
    left.add(statusSummaryLabel);
    panel.add(left, BorderLayout.WEST);

    // Right: Primary execution actions
    JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));

    JButton tuneSelectedBtn = new JButton(tm.getText("btn_tune_now"));
    tuneSelectedBtn.setFont(tuneSelectedBtn.getFont().deriveFont(Font.BOLD));
    tuneSelectedBtn.addActionListener(_ -> tuneSelectedNow());
    right.add(tuneSelectedBtn);

    JButton tuneAllBtn = new JButton(tm.getText("btn_tune_all"));
    tuneAllBtn.addActionListener(_ -> tuneAllConstrained());
    right.add(tuneAllBtn);

    JButton closeBtn = new JButton(tm.getText("btn_close"));
    closeBtn.addActionListener(_ -> setVisible(false));
    right.add(closeBtn);

    panel.add(right, BorderLayout.EAST);
    return panel;
  }

  private void setupListeners() {
    DocumentListener filterDocListener =
        new DocumentListener() {
          @Override
          public void insertUpdate(DocumentEvent e) {
            updateFilter();
          }

          @Override
          public void removeUpdate(DocumentEvent e) {
            updateFilter();
          }

          @Override
          public void changedUpdate(DocumentEvent e) {
            updateFilter();
          }
        };
    filterField.getDocument().addDocumentListener(filterDocListener);
    showConstrainedCheckBox.addActionListener(_ -> updateFilter());
    showUnsatisfiedCheckBox.addActionListener(_ -> updateFilter());

    singleNetTable.getSelectionModel().addListSelectionListener(_ -> onSingleNetSelected());
    diffPairTable.getSelectionModel().addListSelectionListener(_ -> onDiffPairSelected());
    tabbedPane.addChangeListener(_ -> onTabChanged());

    cornerStyleCombo.addActionListener(_ -> updateDiagram());
    singleSidedCheckBox.addActionListener(_ -> updateDiagram());

    DocumentListener geomDocListener =
        new DocumentListener() {
          @Override
          public void insertUpdate(DocumentEvent e) {
            updateDiagram();
          }

          @Override
          public void removeUpdate(DocumentEvent e) {
            updateDiagram();
          }

          @Override
          public void changedUpdate(DocumentEvent e) {
            updateDiagram();
          }
        };
    minAmpField.getDocument().addDocumentListener(geomDocListener);
    maxAmpField.getDocument().addDocumentListener(geomDocListener);
    spacingField.getDocument().addDocumentListener(geomDocListener);
    radiusField.getDocument().addDocumentListener(geomDocListener);
  }

  private void updateFilter() {
    singleNetTableModel.setFilter(
        filterField.getText(),
        showConstrainedCheckBox.isSelected(),
        showUnsatisfiedCheckBox.isSelected());
    updateStatusSummary();
  }

  private void onTabChanged() {
    boolean isDiffTab = tabbedPane.getSelectedIndex() == 1;
    enableTargetConstraintBox.setText(
        isDiffTab ? tm.getText("enable_skew_constraint") : tm.getText("enable_length_constraint"));
    targetLengthField.setEnabled(!isDiffTab && enableTargetConstraintBox.isSelected());
    toleranceField.setEnabled(!isDiffTab && enableTargetConstraintBox.isSelected());
    maxSkewField.setEnabled(isDiffTab && enableTargetConstraintBox.isSelected());

    if (isDiffTab) {
      singleSidedCheckBox.setSelected(true);
      if (diffPairTable.getSelectedRowCount() > 0) {
        onDiffPairSelected();
      } else {
        populateDefaultDiffPairFields();
      }
    } else {
      if (singleNetTable.getSelectedRowCount() > 0) {
        onSingleNetSelected();
      } else {
        populateDefaultSingleNetFields();
      }
    }
    updateDiagram();
  }

  private void onTargetEnableToggled() {
    boolean isDiffTab = tabbedPane.getSelectedIndex() == 1;
    boolean enabled = enableTargetConstraintBox.isSelected();
    targetLengthField.setEnabled(!isDiffTab && enabled);
    toleranceField.setEnabled(!isDiffTab && enabled);
    maxSkewField.setEnabled(isDiffTab && enabled);
  }

  private void onSingleNetSelected() {
    int[] selectedRows = singleNetTable.getSelectedRows();
    if (selectedRows.length == 0) {
      return;
    }

    int modelRow = singleNetTable.convertRowIndexToModel(selectedRows[0]);
    Net net = singleNetTableModel.getNetAt(modelRow);
    if (net == null) {
      return;
    }

    NetLengthConstraint lengthConstraint = net.getLengthConstraint();
    if (lengthConstraint != null && lengthConstraint.isConstrained()) {
      enableTargetConstraintBox.setSelected(true);
      targetLengthField.setText(FMT.format(toUser(lengthConstraint.targetLength())));
      if (lengthConstraint.hasMin() && lengthConstraint.hasMax()) {
        toleranceField.setText(
            FMT.format(
                toUser((lengthConstraint.maxLength() - lengthConstraint.minLength()) / 2.0)));
      } else {
        toleranceField.setText("0.20");
      }
    } else {
      enableTargetConstraintBox.setSelected(false);
      targetLengthField.setText(FMT.format(toUser(net.getTraceLength())));
      toleranceField.setText("0.20");
    }

    NetMeanderConstraint meander = net.getMeanderConstraint();
    if (meander != null) {
      minAmpField.setText(FMT.format(toUser(meander.minAmplitude())));
      maxAmpField.setText(FMT.format(toUser(meander.maxAmplitude())));
      spacingField.setText(FMT.format(toUser(meander.spacing())));
      cornerStyleCombo.setSelectedItem(
          meander.cornerStyle() != null ? meander.cornerStyle().name() : "FILLET");
      radiusField.setText(String.format(Locale.US, "%.1f", meander.radiusPercentage()));
      singleSidedCheckBox.setSelected(meander.singleSided());
    } else {
      populateDefaultMeanderFields(MeanderPatternType.SINGLE_TRACK);
    }

    onTargetEnableToggled();
    updateDiagram();
  }

  private void onDiffPairSelected() {
    int[] selectedRows = diffPairTable.getSelectedRows();
    if (selectedRows.length == 0) {
      return;
    }

    int modelRow = diffPairTable.convertRowIndexToModel(selectedRows[0]);
    DifferentialPair pair = diffPairTableModel.getPairAt(modelRow);
    if (pair == null) {
      return;
    }

    if (pair.hasMaxSkew()) {
      enableTargetConstraintBox.setSelected(true);
      maxSkewField.setText(FMT.format(toUser(pair.maxSkew())));
    } else {
      enableTargetConstraintBox.setSelected(false);
      maxSkewField.setText("0.10"); // Sensible default skew tolerance
    }

    Net shorter = pair.positiveNet();
    if (shorter != null && shorter.getMeanderConstraint() != null) {
      NetMeanderConstraint meander = shorter.getMeanderConstraint();
      minAmpField.setText(FMT.format(toUser(meander.minAmplitude())));
      maxAmpField.setText(FMT.format(toUser(meander.maxAmplitude())));
      spacingField.setText(FMT.format(toUser(meander.spacing())));
      cornerStyleCombo.setSelectedItem(
          meander.cornerStyle() != null ? meander.cornerStyle().name() : "FILLET");
      radiusField.setText(String.format(Locale.US, "%.1f", meander.radiusPercentage()));
      singleSidedCheckBox.setSelected(true);
    } else {
      populateDefaultMeanderFields(MeanderPatternType.DIFF_PAIR_SKEW);
    }

    onTargetEnableToggled();
    updateDiagram();
  }

  private void populateDefaultSingleNetFields() {
    enableTargetConstraintBox.setSelected(false);
    targetLengthField.setText("0.00");
    toleranceField.setText("0.20");
    populateDefaultMeanderFields(MeanderPatternType.SINGLE_TRACK);
  }

  private void populateDefaultDiffPairFields() {
    enableTargetConstraintBox.setSelected(true);
    maxSkewField.setText("0.10");
    populateDefaultMeanderFields(MeanderPatternType.DIFF_PAIR_SKEW);
  }

  private void populateDefaultMeanderFields(MeanderPatternType type) {
    RoutingBoard board = getBoard();
    NetMeanderConstraint preset =
        board != null && board.rules != null ? board.rules.getDefaultMeanderConstraint(type) : null;

    if (preset != null) {
      minAmpField.setText(FMT.format(toUser(preset.minAmplitude())));
      maxAmpField.setText(FMT.format(toUser(preset.maxAmplitude())));
      spacingField.setText(FMT.format(toUser(preset.spacing())));
      cornerStyleCombo.setSelectedItem(preset.cornerStyle().name());
      radiusField.setText(String.format(Locale.US, "%.1f", preset.radiusPercentage()));
      singleSidedCheckBox.setSelected(preset.singleSided());
    } else {
      minAmpField.setText("0.20");
      maxAmpField.setText("1.00");
      spacingField.setText("0.60");
      cornerStyleCombo.setSelectedItem("FILLET");
      radiusField.setText("80.0");
      singleSidedCheckBox.setSelected(type != MeanderPatternType.SINGLE_TRACK);
    }
  }

  private void updateDiagram() {
    double amp = parseDouble(maxAmpField.getText(), 1.0);
    double spacing = parseDouble(spacingField.getText(), 0.6);
    double radiusPct = parseDouble(radiusField.getText(), 80.0);
    CornerStyle style =
        "CHAMFER".equalsIgnoreCase((String) cornerStyleCombo.getSelectedItem())
            ? CornerStyle.CHAMFER
            : CornerStyle.FILLET;
    boolean singleSided = singleSidedCheckBox.isSelected();
    boolean isDiffTab = tabbedPane.getSelectedIndex() == 1;

    diagramPanel.updateParameters(
        style, amp, spacing, radiusPct, singleSided, isDiffTab, getUnitName());
  }

  private void applyRuleToSelected() {
    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }

    double minAmp = parseDouble(minAmpField.getText(), 0.2);
    double maxAmp = parseDouble(maxAmpField.getText(), 1.0);
    double spacing = parseDouble(spacingField.getText(), 0.6);
    CornerStyle style =
        "CHAMFER".equalsIgnoreCase((String) cornerStyleCombo.getSelectedItem())
            ? CornerStyle.CHAMFER
            : CornerStyle.FILLET;
    double radiusPct = parseDouble(radiusField.getText(), 80.0);
    boolean singleSided = singleSidedCheckBox.isSelected();

    NetMeanderConstraint meanderConstraint =
        new NetMeanderConstraint(
            toBoardUnits(minAmp),
            toBoardUnits(maxAmp),
            toBoardUnits(spacing),
            style,
            radiusPct,
            singleSided);

    // Synchronize GUI session settings
    WorkspaceSettings ws = WorkspaceSettings.getOrCreate(board);
    ws.setMeanderMinAmplitudeMm(toMmValue(minAmp));
    ws.setMeanderMaxAmplitudeMm(toMmValue(maxAmp));
    ws.setMeanderSpacingMm(toMmValue(spacing));
    ws.setMeanderCornerStyle(style.name());
    ws.setMeanderRadiusPercentage(radiusPct);
    ws.setMeanderSingleSided(singleSided);

    if (tabbedPane.getSelectedIndex() == 0) {
      // Single Nets tab
      int[] selected = singleNetTable.getSelectedRows();
      if (selected.length == 0) {
        JOptionPane.showMessageDialog(this, tm.getText("no_selection_warning"));
        return;
      }

      boolean enableConstraint = enableTargetConstraintBox.isSelected();
      double target = parseDouble(targetLengthField.getText(), 0.0);
      double tol = parseDouble(toleranceField.getText(), 0.2);

      NetLengthConstraint lengthConstraint = null;
      if (enableConstraint && target > 0.0) {
        double targetBoard = toBoardUnits(target);
        double tolBoard = toBoardUnits(tol);
        lengthConstraint = new NetLengthConstraint(targetBoard - tolBoard, targetBoard + tolBoard);
      }

      for (int sel : selected) {
        int modelRow = singleNetTable.convertRowIndexToModel(sel);
        Net net = singleNetTableModel.getNetAt(modelRow);
        if (net != null) {
          net.setLengthConstraint(lengthConstraint);
          net.setMeanderConstraint(meanderConstraint);
        }
      }
      singleNetTableModel.refreshData(board, getCoordinateTransform());
    } else {
      // Differential Pairs tab
      int[] selected = diffPairTable.getSelectedRows();
      if (selected.length == 0) {
        JOptionPane.showMessageDialog(this, tm.getText("no_selection_warning"));
        return;
      }

      boolean enableConstraint = enableTargetConstraintBox.isSelected();
      double maxSkew = parseDouble(maxSkewField.getText(), 0.10);
      double maxSkewBoard = enableConstraint ? toBoardUnits(maxSkew) : 0.0;

      for (int sel : selected) {
        int modelRow = diffPairTable.convertRowIndexToModel(sel);
        DifferentialPair oldPair = diffPairTableModel.getPairAt(modelRow);
        if (oldPair != null) {
          DifferentialPair updatedPair =
              new DifferentialPair(
                  oldPair.positiveNet(),
                  oldPair.negativeNet(),
                  oldPair.baseName(),
                  oldPair.coupledSpacing() > 0 ? oldPair.coupledSpacing() : toBoardUnits(spacing),
                  maxSkewBoard);
          board.rules.differentialPairs.add(updatedPair);
          if (oldPair.positiveNet() != null) {
            oldPair.positiveNet().setMeanderConstraint(meanderConstraint);
          }
          if (oldPair.negativeNet() != null) {
            oldPair.negativeNet().setMeanderConstraint(meanderConstraint);
          }
        }
      }
      diffPairTableModel.refreshData(board, getCoordinateTransform());
    }

    updateStatusSummary();
  }

  private void matchSelectedToLongest() {
    int[] selected = singleNetTable.getSelectedRows();
    if (selected.length < 2) {
      JOptionPane.showMessageDialog(
          this, "Please select two or more nets in the table to match lengths together.");
      return;
    }

    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }

    double maxLen = 0.0;
    for (int sel : selected) {
      int modelRow = singleNetTable.convertRowIndexToModel(sel);
      Net net = singleNetTableModel.getNetAt(modelRow);
      if (net != null) {
        maxLen = Math.max(maxLen, net.getTraceLength());
      }
    }

    if (maxLen <= 0.0) {
      JOptionPane.showMessageDialog(
          this, "Selected nets have zero routed length. Please route the nets first.");
      return;
    }

    enableTargetConstraintBox.setSelected(true);
    targetLengthField.setText(FMT.format(toUser(maxLen)));
    applyRuleToSelected();
  }

  private void autoDetectDifferentialPairs() {
    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }
    int detected = diffPairTableModel.autoDetectPairs(board);
    tabbedPane.setSelectedIndex(1); // Switch to Diff Pairs tab

    int totalPairs = board.rules.differentialPairs.count();
    String msg =
        detected > 0
            ? String.format(
                Locale.US,
                "Auto-detected and configured %d differential pairs. (Total registered: %d)",
                detected,
                totalPairs)
            : String.format(
                Locale.US,
                "No additional complementary nets detected. (Total registered: %d)",
                totalPairs);

    JOptionPane.showMessageDialog(
        this, msg, "Differential Pair Detection", JOptionPane.INFORMATION_MESSAGE);
    updateStatusSummary();
  }

  private void removeSelectedDifferentialPairs() {
    int[] selected = diffPairTable.getSelectedRows();
    if (selected.length == 0) {
      return;
    }
    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }
    for (int sel : selected) {
      int modelRow = diffPairTable.convertRowIndexToModel(sel);
      DifferentialPair pair = diffPairTableModel.getPairAt(modelRow);
      if (pair != null) {
        board.rules.differentialPairs.remove(pair);
      }
    }
    diffPairTableModel.refreshData(board, getCoordinateTransform());
    updateStatusSummary();
  }

  private void zoomToSelectedNets() {
    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }

    Set<Item> itemsToSelect = new HashSet<>();
    if (tabbedPane.getSelectedIndex() == 0) {
      for (int sel : singleNetTable.getSelectedRows()) {
        int modelRow = singleNetTable.convertRowIndexToModel(sel);
        Net net = singleNetTableModel.getNetAt(modelRow);
        if (net != null) {
          itemsToSelect.addAll(net.getItems());
        }
      }
    } else {
      for (int sel : diffPairTable.getSelectedRows()) {
        int modelRow = diffPairTable.convertRowIndexToModel(sel);
        DifferentialPair pair = diffPairTableModel.getPairAt(modelRow);
        if (pair != null) {
          if (pair.positiveNet() != null) {
            itemsToSelect.addAll(pair.positiveNet().getItems());
          }
          if (pair.negativeNet() != null) {
            itemsToSelect.addAll(pair.negativeNet().getItems());
          }
        }
      }
    }

    if (!itemsToSelect.isEmpty()) {
      GuiBoardManager manager = boardFrame.boardPanel.boardHandling;
      manager.selectItems(itemsToSelect);
      manager.zoomSelection();
    }
  }

  private void tuneSelectedNow() {
    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }

    applyRuleToSelected();
    String unit = getUnitName();

    if (tabbedPane.getSelectedIndex() == 0) {
      // Tune length on single nets
      app.freerouting.autoroute.tuning.PostRouteLengthTuner.TuningReport report =
          app.freerouting.autoroute.tuning.PostRouteLengthTuner.tune(board);

      refresh();
      boardFrame.repaint();
      double userAdded = toUser(report.totalLengthAddedMm() * 10000.0);
      JOptionPane.showMessageDialog(
          this,
          java.text.MessageFormat.format(
              tm.getText("tuning_complete_message"),
              report.totalMeandersInserted(),
              report.netsAttempted(),
              String.format(Locale.US, "%.2f", userAdded),
              unit),
          tm.getText("tuning_complete_title"),
          JOptionPane.INFORMATION_MESSAGE);
    } else {
      // Tune skew on differential pairs
      app.freerouting.autoroute.tuning.DifferentialPairSkewTuner.SkewTuningReport report =
          app.freerouting.autoroute.tuning.DifferentialPairSkewTuner.tune(board);

      refresh();
      boardFrame.repaint();
      JOptionPane.showMessageDialog(
          this,
          java.text.MessageFormat.format(
              tm.getText("skew_complete_message"), report.pairsEqualized(), report.bumpsInserted()),
          tm.getText("skew_complete_title"),
          JOptionPane.INFORMATION_MESSAGE);
    }
  }

  private void tuneAllConstrained() {
    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }
    app.freerouting.autoroute.tuning.DifferentialPairSkewTuner.tune(board);
    app.freerouting.autoroute.tuning.PostRouteLengthTuner.tune(board);
    refresh();
    boardFrame.repaint();
  }

  @Override
  public void refresh() {
    RoutingBoard board = getBoard();
    CoordinateTransform ct = getCoordinateTransform();
    String unit = getUnitName();

    activeUnitBadge.setText("Unit: " + unit);
    targetLengthUnitLabel.setText(unit);
    toleranceUnitLabel.setText(unit);
    maxSkewUnitLabel.setText(unit);
    minAmpUnitLabel.setText(unit);
    maxAmpUnitLabel.setText(unit);
    spacingUnitLabel.setText(unit);

    singleNetTableModel.refreshData(board, ct);
    diffPairTableModel.refreshData(board, ct);

    if (tabbedPane.getSelectedIndex() == 0) {
      if (singleNetTable.getSelectedRowCount() > 0) {
        onSingleNetSelected();
      } else {
        populateDefaultSingleNetFields();
      }
    } else {
      if (diffPairTable.getSelectedRowCount() > 0) {
        onDiffPairSelected();
      } else {
        populateDefaultDiffPairFields();
      }
    }

    updateDiagram();
    updateStatusSummary();
  }

  private void updateStatusSummary() {
    RoutingBoard board = getBoard();
    int pairCount =
        board != null && board.rules != null && board.rules.differentialPairs != null
            ? board.rules.differentialPairs.count()
            : 0;

    statusSummaryLabel.setText(
        String.format(
            Locale.US,
            "Nets: %d | Constrained: %d | Unsatisfied: %d | Diff Pairs: %d",
            singleNetTableModel.getTotalNetCount(),
            singleNetTableModel.getConstrainedCount(),
            singleNetTableModel.getUnsatisfiedCount(),
            pairCount));
  }

  private RoutingBoard getBoard() {
    if (boardFrame != null
        && boardFrame.boardPanel != null
        && boardFrame.boardPanel.boardHandling != null) {
      return boardFrame.boardPanel.boardHandling.getRoutingBoard();
    }
    return null;
  }

  private CoordinateTransform getCoordinateTransform() {
    if (boardFrame != null
        && boardFrame.boardPanel != null
        && boardFrame.boardPanel.boardHandling != null
        && boardFrame.boardPanel.boardHandling.coordinateTransform != null) {
      return boardFrame.boardPanel.boardHandling.coordinateTransform;
    }
    RoutingBoard board = getBoard();
    return LengthTuningTableModel.resolveCoordinateTransform(board);
  }

  private double toUser(double boardUnits) {
    CoordinateTransform ct = getCoordinateTransform();
    return ct != null ? ct.boardToUser(boardUnits) : boardUnits;
  }

  private double toBoardUnits(double userUnits) {
    CoordinateTransform ct = getCoordinateTransform();
    return ct != null ? ct.userToBoard(userUnits) : userUnits;
  }

  private double toMmValue(double userUnits) {
    CoordinateTransform ct = getCoordinateTransform();
    if (ct != null && ct.userUnit != null) {
      return Unit.scale(userUnits, ct.userUnit, Unit.MM);
    }
    return userUnits;
  }

  private String getUnitName() {
    CoordinateTransform ct = getCoordinateTransform();
    return ct != null && ct.userUnit != null ? ct.userUnit.toString() : "mm";
  }

  private static double parseDouble(String text, double defaultValue) {
    if (text == null || text.isBlank()) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(text.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
