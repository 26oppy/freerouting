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
import app.freerouting.rules.DifferentialPairDetector;
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
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
import javax.swing.SwingUtilities;
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
  private final JCheckBox autoTuneOnCompletionCheckBox = new JCheckBox();
  private final JComboBox<String> netClassFilterCombo = new JComboBox<>();
  private final JLabel activeUnitBadge = new JLabel("Unit: mm");

  // Table action buttons & selection badges
  private final JButton linkDiffPairBtn = new JButton();
  private final JButton matchLongestBtn = new JButton();
  private final JButton zoomNetBtn = new JButton();
  private final JLabel singleNetSelectionCountLabel = new JLabel(" ");

  private final JButton addPairBtn = new JButton();
  private final JButton removePairBtn = new JButton();
  private final JButton zoomPairBtn = new JButton();
  private final JLabel diffPairSelectionCountLabel = new JLabel(" ");

  private JScrollPane inspectorScrollPane;

  // Inspector - Targets & Active Selection Header
  private final JLabel inspectorTargetHeaderLabel = new JLabel(" ");
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

    JLabel netClassLabel = new JLabel(tm.getText("net_class"));
    left.add(netClassLabel);
    netClassFilterCombo.addItem("<All Net Classes>");
    left.add(netClassFilterCombo);

    showConstrainedCheckBox.setText(tm.getText("show_only_constrained"));
    showUnsatisfiedCheckBox.setText(tm.getText("show_only_unsatisfied"));
    left.add(showConstrainedCheckBox);
    left.add(showUnsatisfiedCheckBox);

    autoTuneOnCompletionCheckBox.setText(tm.getText("auto_tune_on_completion"));
    autoTuneOnCompletionCheckBox.setToolTipText(tm.getText("auto_tune_tooltip"));
    autoTuneOnCompletionCheckBox.addActionListener(_ -> toggleAutoTuneOnCompletion());
    left.add(autoTuneOnCompletionCheckBox);

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
    // Left: Tab 1 = Single Nets (with select all, clear, bus matching, linking & zoom)
    // -----------------------------------------------------------------------
    singleNetTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    singleNetTable.setAutoCreateRowSorter(true);
    singleNetTable.setToolTipText(tm.getText("double_click_hint"));
    JPanel singleNetPanel = new JPanel(new BorderLayout());
    singleNetPanel.add(new JScrollPane(singleNetTable), BorderLayout.CENTER);

    JPanel singleNetTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));

    JButton selectAllNetsBtn = new JButton(tm.getText("btn_select_all"));
    selectAllNetsBtn.addActionListener(_ -> singleNetTable.selectAll());
    singleNetTools.add(selectAllNetsBtn);

    JButton clearNetsBtn = new JButton(tm.getText("btn_clear_selection"));
    clearNetsBtn.addActionListener(_ -> singleNetTable.clearSelection());
    singleNetTools.add(clearNetsBtn);

    linkDiffPairBtn.setText(tm.getText("btn_link_diff_pair"));
    linkDiffPairBtn.setEnabled(false);
    linkDiffPairBtn.addActionListener(_ -> linkSelectedAsDifferentialPair());
    singleNetTools.add(linkDiffPairBtn);

    matchLongestBtn.setText(tm.getText("btn_match_longest"));
    matchLongestBtn.setEnabled(false);
    matchLongestBtn.setToolTipText(
        "Sets target length of all selected nets to the longest selected trace");
    matchLongestBtn.addActionListener(_ -> matchSelectedToLongest());
    singleNetTools.add(matchLongestBtn);

    zoomNetBtn.setText(tm.getText("btn_zoom"));
    zoomNetBtn.setEnabled(false);
    zoomNetBtn.addActionListener(_ -> zoomToSelectedNets());
    singleNetTools.add(zoomNetBtn);

    singleNetSelectionCountLabel.setForeground(new Color(90, 95, 105));
    singleNetTools.add(singleNetSelectionCountLabel);
    singleNetPanel.add(singleNetTools, BorderLayout.SOUTH);

    // -----------------------------------------------------------------------
    // Left: Tab 2 = Differential Pairs (with select all, clear, add, auto-detect, remove, zoom)
    // -----------------------------------------------------------------------
    diffPairTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    diffPairTable.setAutoCreateRowSorter(true);
    diffPairTable.setToolTipText(tm.getText("double_click_hint"));
    JPanel diffPairPanel = new JPanel(new BorderLayout());
    diffPairPanel.add(new JScrollPane(diffPairTable), BorderLayout.CENTER);

    JPanel diffPairTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));

    JButton selectAllPairsBtn = new JButton(tm.getText("btn_select_all"));
    selectAllPairsBtn.addActionListener(_ -> diffPairTable.selectAll());
    diffPairTools.add(selectAllPairsBtn);

    JButton clearPairsBtn = new JButton(tm.getText("btn_clear_selection"));
    clearPairsBtn.addActionListener(_ -> diffPairTable.clearSelection());
    diffPairTools.add(clearPairsBtn);

    addPairBtn.setText(tm.getText("add_pair"));
    addPairBtn.addActionListener(_ -> openAddDifferentialPairDialog());
    diffPairTools.add(addPairBtn);

    JButton autoDetectBtn = new JButton(tm.getText("auto_detect_pairs"));
    autoDetectBtn.setToolTipText(tm.getText("auto_detect_pairs_tooltip"));
    autoDetectBtn.addActionListener(_ -> autoDetectDifferentialPairs());
    diffPairTools.add(autoDetectBtn);

    removePairBtn.setText(tm.getText("remove_pair"));
    removePairBtn.setEnabled(false);
    removePairBtn.addActionListener(_ -> removeSelectedDifferentialPairs());
    diffPairTools.add(removePairBtn);

    zoomPairBtn.setText(tm.getText("btn_zoom"));
    zoomPairBtn.setEnabled(false);
    zoomPairBtn.addActionListener(_ -> zoomToSelectedNets());
    diffPairTools.add(zoomPairBtn);

    diffPairSelectionCountLabel.setForeground(new Color(90, 95, 105));
    diffPairTools.add(diffPairSelectionCountLabel);
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
    inspectorPanel.add(Box.createVerticalStrut(4));
    inspectorPanel.add(createMeanderGeometrySection());
    inspectorPanel.add(Box.createVerticalStrut(4));

    // Apply button right inside the inspector
    JButton applyBtn = new JButton(tm.getText("btn_apply"));
    applyBtn.setFont(applyBtn.getFont().deriveFont(Font.BOLD));
    applyBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
    applyBtn.addActionListener(_ -> applyRuleToSelected());
    inspectorPanel.add(applyBtn);

    JButton applyToNetClassBtn = new JButton(tm.getText("btn_apply_to_netclass"));
    applyToNetClassBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
    applyToNetClassBtn.addActionListener(_ -> applyProfileToNetClass());
    inspectorPanel.add(Box.createVerticalStrut(4));
    inspectorPanel.add(applyToNetClassBtn);
    inspectorPanel.add(Box.createVerticalStrut(6));

    JLabel previewLabel = new JLabel(tm.getText("label_preview"));
    previewLabel.setFont(previewLabel.getFont().deriveFont(Font.BOLD));
    previewLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    inspectorPanel.add(previewLabel);
    inspectorPanel.add(Box.createVerticalStrut(4));
    diagramPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
    inspectorPanel.add(diagramPanel);

    inspectorScrollPane = new JScrollPane(inspectorPanel);
    inspectorScrollPane.getVerticalScrollBar().setUnitIncrement(16);
    inspectorScrollPane.setBorder(null);

    JSplitPane splitPane =
        new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabbedPane, inspectorScrollPane);
    splitPane.setResizeWeight(0.55);
    splitPane.setDividerLocation(480);
    return splitPane;
  }

  private JPanel createTargetsSection() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Length & Skew Targets"));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(1, 4, 1, 4);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;

    // Header displaying selected net / pair name
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.gridwidth = 3;
    inspectorTargetHeaderLabel.setFont(inspectorTargetHeaderLabel.getFont().deriveFont(Font.BOLD));
    inspectorTargetHeaderLabel.setForeground(new Color(40, 70, 130));
    panel.add(inspectorTargetHeaderLabel, gbc);

    // Enable / Disable Constraint Checkbox
    gbc.gridy = 1;
    enableTargetConstraintBox.setText(tm.getText("enable_length_constraint"));
    enableTargetConstraintBox.setSelected(true);
    enableTargetConstraintBox.addActionListener(_ -> onTargetEnableToggled());
    panel.add(enableTargetConstraintBox, gbc);
    gbc.gridwidth = 1;

    // Target Length
    gbc.gridx = 0;
    gbc.gridy = 2;
    panel.add(new JLabel(tm.getText("target_length")), gbc);
    gbc.gridx = 1;
    panel.add(targetLengthField, gbc);
    gbc.gridx = 2;
    panel.add(targetLengthUnitLabel, gbc);

    // Tolerance
    gbc.gridx = 0;
    gbc.gridy = 3;
    panel.add(new JLabel(tm.getText("tolerance")), gbc);
    gbc.gridx = 1;
    panel.add(toleranceField, gbc);
    gbc.gridx = 2;
    panel.add(toleranceUnitLabel, gbc);

    // Max Skew
    gbc.gridx = 0;
    gbc.gridy = 4;
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
    gbc.insets = new Insets(1, 4, 1, 4);
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

    singleNetTable
        .getSelectionModel()
        .addListSelectionListener(
            _ -> {
              int selCount = singleNetTable.getSelectedRowCount();
              linkDiffPairBtn.setEnabled(selCount == 2);
              matchLongestBtn.setEnabled(selCount >= 2);
              zoomNetBtn.setEnabled(selCount > 0);
              singleNetSelectionCountLabel.setText(
                  selCount > 0
                      ? MessageFormat.format(tm.getText("selected_count"), selCount)
                      : " ");
              if (selCount > 0) {
                onSingleNetSelected();
              } else {
                populateDefaultSingleNetFields();
              }
            });

    singleNetTable.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              zoomToSelectedNets();
            }
          }
        });

    diffPairTable
        .getSelectionModel()
        .addListSelectionListener(
            _ -> {
              int selCount = diffPairTable.getSelectedRowCount();
              removePairBtn.setEnabled(selCount > 0);
              zoomPairBtn.setEnabled(selCount > 0);
              diffPairSelectionCountLabel.setText(
                  selCount > 0
                      ? MessageFormat.format(tm.getText("selected_count"), selCount)
                      : " ");
              if (selCount > 0) {
                onDiffPairSelected();
              } else {
                populateDefaultDiffPairFields();
              }
            });

    diffPairTable.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              zoomToSelectedNets();
            }
          }
        });

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
    netClassFilterCombo.addActionListener(_ -> updateFilter());

    RoutingBoard board = getBoard();
    if (board != null) {
      WorkspaceSettings ws = WorkspaceSettings.getOrCreate(board);
      ws.addPropertyChangeListener(
          WorkspaceSettings.PROP_LENGTH_TUNING,
          evt -> {
            if (evt.getNewValue() instanceof Boolean bool) {
              autoTuneOnCompletionCheckBox.setSelected(bool);
            }
          });
    }
  }

  private void updateFilter() {
    String selectedClass = (String) netClassFilterCombo.getSelectedItem();
    singleNetTableModel.setFilter(
        filterField.getText(),
        showConstrainedCheckBox.isSelected(),
        showUnsatisfiedCheckBox.isSelected(),
        selectedClass);
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
    resetInspectorScroll();
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
      inspectorTargetHeaderLabel.setText(tm.getText("editing_target_none"));
      populateDefaultSingleNetFields();
      return;
    }

    if (selectedRows.length > 1) {
      inspectorTargetHeaderLabel.setText(
          MessageFormat.format(tm.getText("editing_multiple_nets"), selectedRows.length));
    } else {
      int modelRow = singleNetTable.convertRowIndexToModel(selectedRows[0]);
      Net net = singleNetTableModel.getNetAt(modelRow);
      if (net != null) {
        String className = net.getNetClass() != null ? net.getNetClass().getName() : "default";
        inspectorTargetHeaderLabel.setText(
            MessageFormat.format(tm.getText("editing_single_net"), net.name, className));
      }
    }

    int modelRow = singleNetTable.convertRowIndexToModel(selectedRows[0]);
    Net net = singleNetTableModel.getNetAt(modelRow);
    if (net == null) {
      return;
    }

    NetLengthConstraint lengthConstraint = net.getLengthConstraint();
    if (lengthConstraint != null && lengthConstraint.isConstrained()) {
      enableTargetConstraintBox.setSelected(true);
      targetLengthField.setText(formatLengthValue(lengthConstraint.targetLength()));
      if (lengthConstraint.hasMin() && lengthConstraint.hasMax()) {
        toleranceField.setText(
            formatToleranceValue(
                (lengthConstraint.maxLength() - lengthConstraint.minLength()) / 2.0));
      } else {
        toleranceField.setText(getDefaultToleranceString());
      }
    } else {
      enableTargetConstraintBox.setSelected(false);
      targetLengthField.setText(formatLengthValue(net.getTraceLength()));
      toleranceField.setText(getDefaultToleranceString());
    }

    NetMeanderConstraint meander = net.getMeanderConstraint();
    if (meander != null) {
      minAmpField.setText(formatLengthValue(meander.minAmplitude()));
      maxAmpField.setText(formatLengthValue(meander.maxAmplitude()));
      spacingField.setText(formatLengthValue(meander.spacing()));
      cornerStyleCombo.setSelectedItem(
          meander.cornerStyle() != null ? meander.cornerStyle().name() : "FILLET");
      radiusField.setText(String.format(Locale.US, "%.1f", meander.radiusPercentage()));
      singleSidedCheckBox.setSelected(meander.singleSided());
    } else {
      populateDefaultMeanderFields(MeanderPatternType.SINGLE_TRACK);
    }

    onTargetEnableToggled();
    updateDiagram();
    resetInspectorScroll();
  }

  private void onDiffPairSelected() {
    int[] selectedRows = diffPairTable.getSelectedRows();
    if (selectedRows.length == 0) {
      inspectorTargetHeaderLabel.setText(tm.getText("editing_target_none"));
      populateDefaultDiffPairFields();
      return;
    }

    if (selectedRows.length > 1) {
      inspectorTargetHeaderLabel.setText(
          MessageFormat.format(tm.getText("editing_multiple_pairs"), selectedRows.length));
    } else {
      int modelRow = diffPairTable.convertRowIndexToModel(selectedRows[0]);
      DifferentialPair pair = diffPairTableModel.getPairAt(modelRow);
      if (pair != null) {
        String pName = pair.positiveNet() != null ? pair.positiveNet().name : "?";
        String nName = pair.negativeNet() != null ? pair.negativeNet().name : "?";
        inspectorTargetHeaderLabel.setText(
            MessageFormat.format(tm.getText("editing_single_pair"), pair.baseName(), pName, nName));
      }
    }

    int modelRow = diffPairTable.convertRowIndexToModel(selectedRows[0]);
    DifferentialPair pair = diffPairTableModel.getPairAt(modelRow);
    if (pair == null) {
      return;
    }

    if (pair.hasMaxSkew()) {
      enableTargetConstraintBox.setSelected(true);
      maxSkewField.setText(formatSkewValue(pair.maxSkew()));
    } else {
      enableTargetConstraintBox.setSelected(false);
      maxSkewField.setText(getDefaultSkewString());
    }

    Net shorter = pair.positiveNet();
    if (shorter != null && shorter.getMeanderConstraint() != null) {
      NetMeanderConstraint meander = shorter.getMeanderConstraint();
      minAmpField.setText(formatLengthValue(meander.minAmplitude()));
      maxAmpField.setText(formatLengthValue(meander.maxAmplitude()));
      spacingField.setText(formatLengthValue(meander.spacing()));
      cornerStyleCombo.setSelectedItem(
          meander.cornerStyle() != null ? meander.cornerStyle().name() : "FILLET");
      radiusField.setText(String.format(Locale.US, "%.1f", meander.radiusPercentage()));
      singleSidedCheckBox.setSelected(true);
    } else {
      populateDefaultMeanderFields(MeanderPatternType.DIFF_PAIR_SKEW);
    }

    onTargetEnableToggled();
    updateDiagram();
    resetInspectorScroll();
  }

  private void populateDefaultSingleNetFields() {
    inspectorTargetHeaderLabel.setText(tm.getText("editing_target_none"));
    enableTargetConstraintBox.setSelected(false);
    targetLengthField.setText(formatLengthValue(0.0));
    toleranceField.setText(getDefaultToleranceString());
    populateDefaultMeanderFields(MeanderPatternType.SINGLE_TRACK);
  }

  private void populateDefaultDiffPairFields() {
    inspectorTargetHeaderLabel.setText(tm.getText("editing_target_none"));
    enableTargetConstraintBox.setSelected(true);
    maxSkewField.setText(getDefaultSkewString());
    populateDefaultMeanderFields(MeanderPatternType.DIFF_PAIR_SKEW);
  }

  private void populateDefaultMeanderFields(MeanderPatternType type) {
    RoutingBoard board = getBoard();
    NetMeanderConstraint preset =
        board != null && board.rules != null ? board.rules.getDefaultMeanderConstraint(type) : null;

    if (preset != null) {
      minAmpField.setText(formatLengthValue(preset.minAmplitude()));
      maxAmpField.setText(formatLengthValue(preset.maxAmplitude()));
      spacingField.setText(formatLengthValue(preset.spacing()));
      cornerStyleCombo.setSelectedItem(preset.cornerStyle().name());
      radiusField.setText(String.format(Locale.US, "%.1f", preset.radiusPercentage()));
      singleSidedCheckBox.setSelected(preset.singleSided());
    } else {
      Unit u = getUserUnit() != null ? getUserUnit() : Unit.MM;
      minAmpField.setText(formatLengthValue(toBoardUnits(Unit.scale(0.20, Unit.MM, u))));
      maxAmpField.setText(formatLengthValue(toBoardUnits(Unit.scale(1.00, Unit.MM, u))));
      spacingField.setText(formatLengthValue(toBoardUnits(Unit.scale(0.60, Unit.MM, u))));
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
      double maxSkew = parseDouble(maxSkewField.getText(), getDefaultSkewUser());
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
      double enteredTarget = parseDouble(targetLengthField.getText(), 0.0);
      if (enteredTarget <= 0.0) {
        String input =
            JOptionPane.showInputDialog(
                this,
                java.text.MessageFormat.format(tm.getText("match_unrouted_prompt"), getUnitName()),
                tm.getText("match_unrouted_title"),
                JOptionPane.QUESTION_MESSAGE);
        if (input == null || input.isBlank()) {
          return;
        }
        enteredTarget = parseDouble(input, 0.0);
        if (enteredTarget <= 0.0) {
          return;
        }
        targetLengthField.setText(FMT.format(enteredTarget));
      }
      maxLen = toBoardUnits(enteredTarget);
    } else {
      targetLengthField.setText(FMT.format(toUser(maxLen)));
    }

    enableTargetConstraintBox.setSelected(true);
    applyRuleToSelected();
  }

  private void applyProfileToNetClass() {
    RoutingBoard board = getBoard();
    if (board == null || board.rules == null || board.rules.netClasses == null) {
      return;
    }

    String selectedClass = (String) netClassFilterCombo.getSelectedItem();
    if (selectedClass == null
        || selectedClass.isBlank()
        || selectedClass.equalsIgnoreCase("<All Net Classes>")) {
      List<String> classNames = new ArrayList<>();
      for (int i = 0; i < board.rules.netClasses.count(); i++) {
        var nc = board.rules.netClasses.get(i);
        if (nc != null && nc.getName() != null && !nc.getName().isBlank()) {
          classNames.add(nc.getName());
        }
      }
      if (classNames.isEmpty()) {
        JOptionPane.showMessageDialog(
            this,
            "No Net Classes found on board.",
            "Net Class Profile",
            JOptionPane.WARNING_MESSAGE);
        return;
      }
      String chosen =
          (String)
              JOptionPane.showInputDialog(
                  this,
                  "Select Net Class to assign this tuning profile to:",
                  "Assign Tuning Profile to Net Class",
                  JOptionPane.QUESTION_MESSAGE,
                  null,
                  classNames.toArray(new String[0]),
                  classNames.get(0));
      if (chosen == null) {
        return;
      }
      selectedClass = chosen;
    }

    app.freerouting.rules.NetClass netClass = board.rules.netClasses.get(selectedClass);
    if (netClass == null) {
      return;
    }

    boolean enableConstraint = enableTargetConstraintBox.isSelected();
    double target = parseDouble(targetLengthField.getText(), 0.0);
    double tol = parseDouble(toleranceField.getText(), 0.2);

    NetLengthConstraint lengthConstraint;
    if (enableConstraint && target > 0.0) {
      double targetBoard = toBoardUnits(target);
      double tolBoard = toBoardUnits(tol);
      lengthConstraint = new NetLengthConstraint(targetBoard - tolBoard, targetBoard + tolBoard);
    } else {
      lengthConstraint = NetLengthConstraint.UNCONSTRAINED;
    }

    double minAmp = parseDouble(minAmpField.getText(), 0.5);
    double maxAmp = parseDouble(maxAmpField.getText(), 2.0);
    double spacing = parseDouble(spacingField.getText(), 1.0);
    CornerStyle cornerStyle = CornerStyle.valueOf((String) cornerStyleCombo.getSelectedItem());
    double radiusPct = parseDouble(radiusField.getText(), 80.0);
    boolean singleSided = singleSidedCheckBox.isSelected();

    NetMeanderConstraint meanderConstraint =
        new NetMeanderConstraint(
            toBoardUnits(minAmp),
            toBoardUnits(maxAmp),
            toBoardUnits(spacing),
            cornerStyle,
            radiusPct,
            singleSided);

    netClass.setLengthConstraint(lengthConstraint);
    netClass.setMeanderConstraint(meanderConstraint);

    refresh();
    JOptionPane.showMessageDialog(
        this,
        "Assigned tuning profile to Net Class '"
            + selectedClass
            + "'. All member nets will inherit these rules.",
        "Net Class Profile Assigned",
        JOptionPane.INFORMATION_MESSAGE);
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

    String unit = getUnitName();

    if (tabbedPane.getSelectedIndex() == 0) {
      int[] selected = singleNetTable.getSelectedRows();
      if (selected.length == 0) {
        JOptionPane.showMessageDialog(this, tm.getText("no_selection_warning"));
        return;
      }
      applyRuleToSelected();

      List<Net> netsToTune = new ArrayList<>();
      for (int sel : selected) {
        int modelRow = singleNetTable.convertRowIndexToModel(sel);
        Net net = singleNetTableModel.getNetAt(modelRow);
        if (net != null) {
          netsToTune.add(net);
        }
      }

      app.freerouting.autoroute.tuning.PostRouteLengthTuner.TuningReport report =
          app.freerouting.autoroute.tuning.PostRouteLengthTuner.tune(board, netsToTune);

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
      int[] selected = diffPairTable.getSelectedRows();
      if (selected.length == 0) {
        JOptionPane.showMessageDialog(this, tm.getText("no_selection_warning"));
        return;
      }
      applyRuleToSelected();

      List<DifferentialPair> pairsToTune = new ArrayList<>();
      for (int sel : selected) {
        int modelRow = diffPairTable.convertRowIndexToModel(sel);
        DifferentialPair pair = diffPairTableModel.getPairAt(modelRow);
        if (pair != null) {
          pairsToTune.add(pair);
        }
      }

      app.freerouting.autoroute.tuning.DifferentialPairSkewTuner.SkewTuningReport report =
          app.freerouting.autoroute.tuning.DifferentialPairSkewTuner.tune(board, pairsToTune);

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

    if (board != null) {
      WorkspaceSettings ws = WorkspaceSettings.getOrCreate(board);
      autoTuneOnCompletionCheckBox.setSelected(Boolean.TRUE.equals(ws.isLengthTuningEnabled()));

      if (board.rules != null && board.rules.netClasses != null) {
        Object prev = netClassFilterCombo.getSelectedItem();
        ActionListener[] listeners = netClassFilterCombo.getActionListeners();
        for (ActionListener al : listeners) {
          netClassFilterCombo.removeActionListener(al);
        }
        netClassFilterCombo.removeAllItems();
        netClassFilterCombo.addItem("<All Net Classes>");
        for (int i = 0; i < board.rules.netClasses.count(); i++) {
          var nc = board.rules.netClasses.get(i);
          if (nc != null && nc.getName() != null && !nc.getName().isBlank()) {
            netClassFilterCombo.addItem(nc.getName());
          }
        }
        if (prev != null) {
          netClassFilterCombo.setSelectedItem(prev);
        }
        for (ActionListener al : listeners) {
          netClassFilterCombo.addActionListener(al);
        }
      }
    }

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
    resetInspectorScroll();
  }

  private void toggleAutoTuneOnCompletion() {
    RoutingBoard board = getBoard();
    boolean selected = autoTuneOnCompletionCheckBox.isSelected();
    if (board != null) {
      WorkspaceSettings ws = WorkspaceSettings.getOrCreate(board);
      ws.setLengthTuningEnabled(selected);
    }
    if (boardFrame != null
        && boardFrame.boardPanel != null
        && boardFrame.boardPanel.boardHandling != null
        && boardFrame.boardPanel.boardHandling.getCurrentRoutingJob() != null) {
      boardFrame
          .boardPanel
          .boardHandling
          .getCurrentRoutingJob()
          .routerSettings
          .setLengthTuningEnabled(selected);
    }
  }

  private void resetInspectorScroll() {
    if (inspectorScrollPane != null) {
      SwingUtilities.invokeLater(() -> inspectorScrollPane.getVerticalScrollBar().setValue(0));
    }
  }

  private Unit getUserUnit() {
    CoordinateTransform ct = getCoordinateTransform();
    return ct != null && ct.userUnit != null ? ct.userUnit : Unit.MM;
  }

  private double getDefaultSkewUser() {
    Unit u = getUserUnit();
    return Unit.scale(0.10, Unit.MM, (u != null ? u : Unit.MM));
  }

  private double getDefaultToleranceUser() {
    Unit u = getUserUnit();
    return Unit.scale(0.20, Unit.MM, (u != null ? u : Unit.MM));
  }

  private String getDefaultToleranceString() {
    Unit u = getUserUnit();
    if (u == null) {
      u = Unit.MM;
    }
    double defaultTolUser = getDefaultToleranceUser();
    return switch (u) {
      case INCH -> String.format(Locale.US, "%.4f", defaultTolUser);
      case MIL, MM -> String.format(Locale.US, "%.3f", defaultTolUser);
      case UM -> String.format(Locale.US, "%.1f", defaultTolUser);
    };
  }

  private String getDefaultSkewString() {
    Unit u = getUserUnit();
    if (u == null) {
      u = Unit.MM;
    }
    double defaultSkewUser = getDefaultSkewUser();
    return switch (u) {
      case INCH -> String.format(Locale.US, "%.4f", defaultSkewUser);
      case MIL, MM -> String.format(Locale.US, "%.3f", defaultSkewUser);
      case UM -> String.format(Locale.US, "%.1f", defaultSkewUser);
    };
  }

  private String formatLengthValue(double boardUnits) {
    double userVal = toUser(boardUnits);
    Unit u = getUserUnit();
    if (u == null) {
      u = Unit.MM;
    }
    return switch (u) {
      case INCH -> String.format(Locale.US, "%.4f", userVal);
      case MIL, MM -> String.format(Locale.US, "%.2f", userVal);
      case UM -> String.format(Locale.US, "%.1f", userVal);
    };
  }

  private String formatToleranceValue(double boardUnits) {
    double userVal = toUser(boardUnits);
    Unit u = getUserUnit();
    if (u == null) {
      u = Unit.MM;
    }
    return switch (u) {
      case INCH -> String.format(Locale.US, "%.4f", userVal);
      case MIL, MM -> String.format(Locale.US, "%.3f", userVal);
      case UM -> String.format(Locale.US, "%.1f", userVal);
    };
  }

  private String formatSkewValue(double boardUnits) {
    double userVal = toUser(boardUnits);
    Unit u = getUserUnit();
    if (u == null) {
      u = Unit.MM;
    }
    return switch (u) {
      case INCH -> String.format(Locale.US, "%.4f", userVal);
      case MIL, MM -> String.format(Locale.US, "%.3f", userVal);
      case UM -> String.format(Locale.US, "%.1f", userVal);
    };
  }

  private void linkSelectedAsDifferentialPair() {
    int[] selected = singleNetTable.getSelectedRows();
    if (selected.length != 2) {
      JOptionPane.showMessageDialog(
          this,
          tm.getText("select_exact_two_nets_warning"),
          tm.getText("add_pair_dialog_title"),
          JOptionPane.WARNING_MESSAGE);
      return;
    }

    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }

    int modelRow1 = singleNetTable.convertRowIndexToModel(selected[0]);
    int modelRow2 = singleNetTable.convertRowIndexToModel(selected[1]);
    Net net1 = singleNetTableModel.getNetAt(modelRow1);
    Net net2 = singleNetTableModel.getNetAt(modelRow2);
    if (net1 == null || net2 == null) {
      return;
    }

    Net posNet = net1;
    Net negNet = net2;
    var info1 = DifferentialPairDetector.extract(net1.name);
    var info2 = DifferentialPairDetector.extract(net2.name);
    String proposedName = null;
    if (info1 != null && info1.polarity() == DifferentialPairDetector.Polarity.NEGATIVE) {
      posNet = net2;
      negNet = net1;
      proposedName = info1.baseName();
    } else if (info1 != null) {
      proposedName = info1.baseName();
    } else if (info2 != null) {
      proposedName = info2.baseName();
    }
    if (proposedName == null || proposedName.isBlank()) {
      proposedName = deduceCommonPrefix(net1.name, net2.name);
    }
    if (proposedName.isBlank()) {
      proposedName = net1.name + "_" + net2.name;
    }

    String pairName =
        (String)
            JOptionPane.showInputDialog(
                this,
                java.text.MessageFormat.format(
                    tm.getText("link_pair_prompt"), posNet.name, negNet.name),
                tm.getText("add_pair_dialog_title"),
                JOptionPane.PLAIN_MESSAGE,
                null,
                null,
                proposedName);

    if (pairName == null || pairName.trim().isEmpty()) {
      return;
    }
    pairName = pairName.trim();

    NetMeanderConstraint skewPreset =
        board.rules.getDefaultMeanderConstraint(MeanderPatternType.DIFF_PAIR_SKEW);
    double defaultSkewBoard = toBoardUnits(getDefaultSkewUser());
    double defaultSpacingBoard =
        skewPreset != null && skewPreset.spacing() > 0 ? skewPreset.spacing() : 6000.0;

    DifferentialPair pair =
        new DifferentialPair(posNet, negNet, pairName, defaultSpacingBoard, defaultSkewBoard);
    board.rules.differentialPairs.add(pair);

    if (posNet.getMeanderConstraint() == null) {
      posNet.setMeanderConstraint(skewPreset);
    }
    if (negNet.getMeanderConstraint() == null) {
      negNet.setMeanderConstraint(skewPreset);
    }

    tabbedPane.setSelectedIndex(1);
    refresh();

    for (int i = 0; i < diffPairTable.getRowCount(); i++) {
      int mRow = diffPairTable.convertRowIndexToModel(i);
      DifferentialPair p = diffPairTableModel.getPairAt(mRow);
      if (p != null && pairName.equalsIgnoreCase(p.baseName())) {
        diffPairTable.setRowSelectionInterval(i, i);
        diffPairTable.scrollRectToVisible(diffPairTable.getCellRect(i, 0, true));
        break;
      }
    }
  }

  private void openAddDifferentialPairDialog() {
    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }

    List<String> netNames = new ArrayList<>();
    int maxNet = board.rules.nets.maxNetNumber();
    for (int i = 1; i <= maxNet; i++) {
      Net n = board.rules.nets.get(i);
      if (n != null && n.name != null && !n.name.isBlank()) {
        netNames.add(n.name);
      }
    }
    Collections.sort(netNames);

    if (netNames.size() < 2) {
      JOptionPane.showMessageDialog(this, "Not enough nets on board to create a pair.");
      return;
    }

    JComboBox<String> posCombo = new JComboBox<>(netNames.toArray(new String[0]));
    JComboBox<String> negCombo = new JComboBox<>(netNames.toArray(new String[0]));
    if (netNames.size() > 1) {
      negCombo.setSelectedIndex(1);
    }

    JTextField nameField = new JTextField(15);
    JTextField skewField = new JTextField(getDefaultSkewString(), 8);

    Runnable updateSuggestedName =
        () -> {
          String n1 = (String) posCombo.getSelectedItem();
          String n2 = (String) negCombo.getSelectedItem();
          if (n1 != null && n2 != null) {
            String base = deduceCommonPrefix(n1, n2);
            if (base.isBlank()) {
              base = n1 + "_" + n2;
            }
            nameField.setText(base);
          }
        };
    updateSuggestedName.run();
    posCombo.addActionListener(_ -> updateSuggestedName.run());
    negCombo.addActionListener(_ -> updateSuggestedName.run());

    JPanel dialogPanel = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(4, 4, 4, 4);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;

    gbc.gridx = 0;
    gbc.gridy = 0;
    dialogPanel.add(new JLabel(tm.getText("select_positive_net")), gbc);
    gbc.gridx = 1;
    dialogPanel.add(posCombo, gbc);

    gbc.gridx = 0;
    gbc.gridy = 1;
    dialogPanel.add(new JLabel(tm.getText("select_negative_net")), gbc);
    gbc.gridx = 1;
    dialogPanel.add(negCombo, gbc);

    gbc.gridx = 0;
    gbc.gridy = 2;
    dialogPanel.add(new JLabel(tm.getText("pair_name")), gbc);
    gbc.gridx = 1;
    dialogPanel.add(nameField, gbc);

    gbc.gridx = 0;
    gbc.gridy = 3;
    dialogPanel.add(new JLabel(tm.getText("initial_skew_tolerance")), gbc);
    JPanel skewPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    skewPanel.add(skewField);
    skewPanel.add(new JLabel(getUnitName()));
    gbc.gridx = 1;
    dialogPanel.add(skewPanel, gbc);

    int result =
        JOptionPane.showConfirmDialog(
            this,
            dialogPanel,
            tm.getText("add_pair_dialog_title"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);

    if (result == JOptionPane.OK_OPTION) {
      String posName = (String) posCombo.getSelectedItem();
      String negName = (String) negCombo.getSelectedItem();
      if (posName == null || negName == null || posName.equalsIgnoreCase(negName)) {
        JOptionPane.showMessageDialog(this, tm.getText("select_two_distinct_nets_warning"));
        return;
      }
      String pName = nameField.getText().trim();
      if (pName.isEmpty()) {
        pName = posName + "_" + negName;
      }

      Net pNet = board.rules.nets.get(posName, 1);
      Net nNet = board.rules.nets.get(negName, 1);
      if (pNet == null || nNet == null) {
        return;
      }

      NetMeanderConstraint skewPreset =
          board.rules.getDefaultMeanderConstraint(MeanderPatternType.DIFF_PAIR_SKEW);
      double maxSkewUser = parseDouble(skewField.getText(), getDefaultSkewUser());
      double maxSkewBoard = toBoardUnits(maxSkewUser);
      double defaultSpacingBoard =
          skewPreset != null && skewPreset.spacing() > 0 ? skewPreset.spacing() : 6000.0;

      DifferentialPair pair =
          new DifferentialPair(pNet, nNet, pName, defaultSpacingBoard, maxSkewBoard);
      board.rules.differentialPairs.add(pair);

      if (pNet.getMeanderConstraint() == null) {
        pNet.setMeanderConstraint(skewPreset);
      }
      if (nNet.getMeanderConstraint() == null) {
        nNet.setMeanderConstraint(skewPreset);
      }

      tabbedPane.setSelectedIndex(1);
      refresh();

      for (int i = 0; i < diffPairTable.getRowCount(); i++) {
        int mRow = diffPairTable.convertRowIndexToModel(i);
        DifferentialPair p = diffPairTableModel.getPairAt(mRow);
        if (p != null && pName.equalsIgnoreCase(p.baseName())) {
          diffPairTable.setRowSelectionInterval(i, i);
          diffPairTable.scrollRectToVisible(diffPairTable.getCellRect(i, 0, true));
          break;
        }
      }
    }
  }

  private static String deduceCommonPrefix(String s1, String s2) {
    if (s1 == null || s2 == null) {
      return "";
    }
    int len = Math.min(s1.length(), s2.length());
    int i = 0;
    while (i < len && s1.charAt(i) == s2.charAt(i)) {
      i++;
    }
    String prefix = s1.substring(0, i);
    while (prefix.endsWith("_")
        || prefix.endsWith(".")
        || prefix.endsWith("-")
        || prefix.endsWith("/")) {
      prefix = prefix.substring(0, prefix.length() - 1);
    }
    return prefix;
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
