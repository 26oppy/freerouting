package app.freerouting.gui.windows.routing;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.gui.board.BoardFrame;
import app.freerouting.gui.board.BoardSavableSubWindow;
import app.freerouting.gui.workspace.GuiBoardManager;
import app.freerouting.rules.CornerStyle;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetLengthConstraint;
import app.freerouting.rules.NetMeanderConstraint;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
import javax.swing.JSlider;
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

  private final JTextField targetLengthField = new JTextField(7);
  private final JTextField toleranceField = new JTextField(7);
  private final JTextField maxSkewField = new JTextField(7);

  private final JTextField minAmpField = new JTextField(6);
  private final JTextField maxAmpField = new JTextField(6);
  private final JTextField spacingField = new JTextField(6);
  private final JComboBox<String> cornerStyleCombo =
      new JComboBox<>(new String[] {"FILLET", "CHAMFER"});
  private final JSlider radiusSlider = new JSlider(0, 100, 80);
  private final JLabel radiusLabel = new JLabel("80%");
  private final JCheckBox singleSidedCheckBox = new JCheckBox();

  private final JTabbedPane tabbedPane = new JTabbedPane();

  /** Creates a new instance of WindowLengthTuning. */
  public WindowLengthTuning(BoardFrame boardFrame) {
    this.boardFrame = boardFrame;
    setLanguage(boardFrame.getLocale());

    this.setTitle(tm.getText("title"));
    this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    this.setPreferredSize(new Dimension(860, 580));
    this.setMinimumSize(new Dimension(680, 420));

    JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
    mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    // North Panel: Filters & Search
    mainPanel.add(createFilterPanel(), BorderLayout.NORTH);

    // Center Panel: Split Pane (Left = Tables, Right = Inspector & Diagram)
    mainPanel.add(createCenterSplitPane(), BorderLayout.CENTER);

    // South Panel: Action Buttons
    mainPanel.add(createActionToolbar(), BorderLayout.SOUTH);

    getContentPane().add(mainPanel);
    pack();

    setupListeners();
    refresh();
  }

  private JPanel createFilterPanel() {
    JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
    JLabel filterLabel = new JLabel(tm.getText("filter"));
    filterPanel.add(filterLabel);
    filterPanel.add(filterField);

    showConstrainedCheckBox.setText(tm.getText("show_only_constrained"));
    showUnsatisfiedCheckBox.setText(tm.getText("show_only_unsatisfied"));
    filterPanel.add(showConstrainedCheckBox);
    filterPanel.add(showUnsatisfiedCheckBox);

    return filterPanel;
  }

  private JSplitPane createCenterSplitPane() {
    // Left: Tabs for Single Nets and Differential Pairs
    singleNetTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    singleNetTable.setAutoCreateRowSorter(true);
    JScrollPane singleNetScroll = new JScrollPane(singleNetTable);

    diffPairTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    diffPairTable.setAutoCreateRowSorter(true);
    JPanel diffPairPanel = new JPanel(new BorderLayout());
    diffPairPanel.add(new JScrollPane(diffPairTable), BorderLayout.CENTER);

    JPanel diffPairToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
    JButton autoDetectBtn = new JButton(tm.getText("auto_detect_pairs"));
    autoDetectBtn.setToolTipText(tm.getText("auto_detect_pairs_tooltip"));
    autoDetectBtn.addActionListener(_ -> autoDetectDifferentialPairs());
    diffPairToolbar.add(autoDetectBtn);

    JButton removePairBtn = new JButton(tm.getText("remove_pair"));
    removePairBtn.addActionListener(_ -> removeSelectedDifferentialPairs());
    diffPairToolbar.add(removePairBtn);
    diffPairPanel.add(diffPairToolbar, BorderLayout.SOUTH);

    tabbedPane.addTab(tm.getText("tab_single_nets"), singleNetScroll);
    tabbedPane.addTab(tm.getText("tab_diff_pairs"), diffPairPanel);

    // Right: Inspector Controls & Live Diagram
    JPanel inspectorPanel = new JPanel();
    inspectorPanel.setLayout(new BoxLayout(inspectorPanel, BoxLayout.Y_AXIS));
    inspectorPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 5));

    inspectorPanel.add(createConstraintsSection());
    inspectorPanel.add(Box.createVerticalStrut(8));
    inspectorPanel.add(createMeanderGeometrySection());
    inspectorPanel.add(Box.createVerticalStrut(8));

    JLabel previewLabel = new JLabel(tm.getText("label_preview"));
    previewLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    inspectorPanel.add(previewLabel);
    inspectorPanel.add(Box.createVerticalStrut(4));
    diagramPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
    inspectorPanel.add(diagramPanel);

    JSplitPane splitPane =
        new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabbedPane, new JScrollPane(inspectorPanel));
    splitPane.setResizeWeight(0.55);
    splitPane.setDividerLocation(470);
    return splitPane;
  }

  private JPanel createConstraintsSection() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Length & Skew Targets"));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(3, 5, 3, 5);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;

    gbc.gridx = 0;
    gbc.gridy = 0;
    panel.add(new JLabel(tm.getText("target_length")), gbc);
    gbc.gridx = 1;
    panel.add(targetLengthField, gbc);
    gbc.gridx = 2;
    panel.add(new JLabel("mm"), gbc);

    gbc.gridx = 0;
    gbc.gridy = 1;
    panel.add(new JLabel(tm.getText("tolerance")), gbc);
    gbc.gridx = 1;
    panel.add(toleranceField, gbc);
    gbc.gridx = 2;
    panel.add(new JLabel("mm"), gbc);

    gbc.gridx = 0;
    gbc.gridy = 2;
    panel.add(new JLabel(tm.getText("max_skew")), gbc);
    gbc.gridx = 1;
    panel.add(maxSkewField, gbc);
    gbc.gridx = 2;
    panel.add(new JLabel("mm"), gbc);

    return panel;
  }

  private JPanel createMeanderGeometrySection() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBorder(BorderFactory.createTitledBorder("Meander Geometry"));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(2, 5, 2, 5);
    gbc.anchor = GridBagConstraints.WEST;
    gbc.fill = GridBagConstraints.HORIZONTAL;

    gbc.gridx = 0;
    gbc.gridy = 0;
    panel.add(new JLabel(tm.getText("min_amplitude")), gbc);
    gbc.gridx = 1;
    panel.add(minAmpField, gbc);
    gbc.gridx = 2;
    panel.add(new JLabel("mm"), gbc);

    gbc.gridx = 0;
    gbc.gridy = 1;
    panel.add(new JLabel(tm.getText("max_amplitude")), gbc);
    gbc.gridx = 1;
    panel.add(maxAmpField, gbc);
    gbc.gridx = 2;
    panel.add(new JLabel("mm"), gbc);

    gbc.gridx = 0;
    gbc.gridy = 2;
    panel.add(new JLabel(tm.getText("spacing")), gbc);
    gbc.gridx = 1;
    panel.add(spacingField, gbc);
    gbc.gridx = 2;
    panel.add(new JLabel("mm"), gbc);

    gbc.gridx = 0;
    gbc.gridy = 3;
    panel.add(new JLabel(tm.getText("corner_style")), gbc);
    gbc.gridx = 1;
    gbc.gridwidth = 2;
    panel.add(cornerStyleCombo, gbc);
    gbc.gridwidth = 1;

    gbc.gridx = 0;
    gbc.gridy = 4;
    panel.add(new JLabel(tm.getText("radius_percentage")), gbc);
    gbc.gridx = 1;
    panel.add(radiusSlider, gbc);
    gbc.gridx = 2;
    panel.add(radiusLabel, gbc);

    gbc.gridx = 0;
    gbc.gridy = 5;
    panel.add(new JLabel(tm.getText("side")), gbc);
    gbc.gridx = 1;
    gbc.gridwidth = 2;
    singleSidedCheckBox.setText(tm.getText("side_single"));
    panel.add(singleSidedCheckBox, gbc);

    return panel;
  }

  private JPanel createActionToolbar() {
    JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));

    JButton applyBtn = new JButton(tm.getText("btn_apply"));
    applyBtn.addActionListener(_ -> applyRuleToSelected());
    toolbar.add(applyBtn);

    JButton matchLongestBtn = new JButton(tm.getText("btn_match_longest"));
    matchLongestBtn.addActionListener(_ -> matchSelectedToLongest());
    toolbar.add(matchLongestBtn);

    JButton zoomBtn = new JButton(tm.getText("btn_zoom"));
    zoomBtn.addActionListener(_ -> zoomToSelectedNets());
    toolbar.add(zoomBtn);

    JButton tuneSelectedBtn = new JButton(tm.getText("btn_tune_now"));
    tuneSelectedBtn.addActionListener(_ -> tuneSelectedNow());
    toolbar.add(tuneSelectedBtn);

    JButton tuneAllBtn = new JButton(tm.getText("btn_tune_all"));
    tuneAllBtn.addActionListener(_ -> tuneAllConstrained());
    toolbar.add(tuneAllBtn);

    JButton closeBtn = new JButton(tm.getText("btn_close"));
    closeBtn.addActionListener(_ -> setVisible(false));
    toolbar.add(closeBtn);

    return toolbar;
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

    // Parameter changes update the live vector diagram
    cornerStyleCombo.addActionListener(_ -> updateDiagram());
    radiusSlider.addChangeListener(
        _ -> {
          radiusLabel.setText(radiusSlider.getValue() + "%");
          updateDiagram();
        });
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
  }

  private void updateFilter() {
    singleNetTableModel.setFilter(
        filterField.getText(),
        showConstrainedCheckBox.isSelected(),
        showUnsatisfiedCheckBox.isSelected());
  }

  private void onTabChanged() {
    boolean isDiffTab = tabbedPane.getSelectedIndex() == 1;
    targetLengthField.setEnabled(!isDiffTab);
    toleranceField.setEnabled(!isDiffTab);
    maxSkewField.setEnabled(isDiffTab);
    updateDiagram();
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
    RoutingBoard board = getBoard();
    if (lengthConstraint != null && lengthConstraint.isConstrained()) {
      targetLengthField.setText(FMT.format(toUser(board, lengthConstraint.targetLength())));
      if (lengthConstraint.hasMin() && lengthConstraint.hasMax()) {
        toleranceField.setText(
            FMT.format(
                toUser(
                    board, (lengthConstraint.maxLength() - lengthConstraint.minLength()) / 2.0)));
      } else {
        toleranceField.setText("0.20");
      }
    } else {
      targetLengthField.setText(FMT.format(toUser(board, net.getTraceLength())));
      toleranceField.setText("0.20");
    }

    NetMeanderConstraint meander = net.getMeanderConstraint();
    if (meander != null) {
      minAmpField.setText(FMT.format(toUser(board, meander.minAmplitude())));
      maxAmpField.setText(FMT.format(toUser(board, meander.maxAmplitude())));
      spacingField.setText(FMT.format(toUser(board, meander.spacing())));
      cornerStyleCombo.setSelectedItem(
          meander.cornerStyle() != null ? meander.cornerStyle().name() : "FILLET");
      radiusSlider.setValue((int) Math.round(meander.radiusPercentage()));
      singleSidedCheckBox.setSelected(meander.singleSided());
    }

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

    RoutingBoard board = getBoard();
    maxSkewField.setText(FMT.format(toUser(board, pair.maxSkew())));

    Net shorter = pair.positiveNet();
    if (shorter != null && shorter.getMeanderConstraint() != null) {
      NetMeanderConstraint meander = shorter.getMeanderConstraint();
      minAmpField.setText(FMT.format(toUser(board, meander.minAmplitude())));
      maxAmpField.setText(FMT.format(toUser(board, meander.maxAmplitude())));
      spacingField.setText(FMT.format(toUser(board, meander.spacing())));
      cornerStyleCombo.setSelectedItem(
          meander.cornerStyle() != null ? meander.cornerStyle().name() : "FILLET");
      radiusSlider.setValue((int) Math.round(meander.radiusPercentage()));
      singleSidedCheckBox.setSelected(true); // Diff pair bumps are naturally single sided
    }

    updateDiagram();
  }

  private void updateDiagram() {
    double amp = parseDouble(maxAmpField.getText(), 1.0);
    double spacing = parseDouble(spacingField.getText(), 0.6);
    double radiusPct = radiusSlider.getValue();
    CornerStyle style =
        "CHAMFER".equalsIgnoreCase((String) cornerStyleCombo.getSelectedItem())
            ? CornerStyle.CHAMFER
            : CornerStyle.FILLET;
    boolean singleSided = singleSidedCheckBox.isSelected();
    boolean isDiffTab = tabbedPane.getSelectedIndex() == 1;

    diagramPanel.updateParameters(style, amp, spacing, radiusPct, singleSided, isDiffTab);
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
    double radiusPct = radiusSlider.getValue();
    boolean singleSided = singleSidedCheckBox.isSelected();

    NetMeanderConstraint meanderConstraint =
        new NetMeanderConstraint(
            toBoardUnits(board, minAmp),
            toBoardUnits(board, maxAmp),
            toBoardUnits(board, spacing),
            style,
            radiusPct,
            singleSided);

    if (tabbedPane.getSelectedIndex() == 0) {
      // Single Nets tab
      int[] selected = singleNetTable.getSelectedRows();
      if (selected.length == 0) {
        JOptionPane.showMessageDialog(this, tm.getText("no_selection_warning"));
        return;
      }

      double target = parseDouble(targetLengthField.getText(), 0.0);
      double tol = parseDouble(toleranceField.getText(), 0.2);
      if (target <= 0.0) {
        return;
      }

      double targetBoard = toBoardUnits(board, target);
      double tolBoard = toBoardUnits(board, tol);
      NetLengthConstraint lengthConstraint =
          new NetLengthConstraint(targetBoard - tolBoard, targetBoard + tolBoard);

      for (int sel : selected) {
        int modelRow = singleNetTable.convertRowIndexToModel(sel);
        Net net = singleNetTableModel.getNetAt(modelRow);
        if (net != null) {
          net.setLengthConstraint(lengthConstraint);
          net.setMeanderConstraint(meanderConstraint);
        }
      }
      singleNetTableModel.refreshData(board);
    } else {
      // Differential Pairs tab
      int[] selected = diffPairTable.getSelectedRows();
      if (selected.length == 0) {
        JOptionPane.showMessageDialog(this, tm.getText("no_selection_warning"));
        return;
      }

      double maxSkew = parseDouble(maxSkewField.getText(), 0.05);
      double maxSkewBoard = toBoardUnits(board, maxSkew);

      for (int sel : selected) {
        int modelRow = diffPairTable.convertRowIndexToModel(sel);
        DifferentialPair oldPair = diffPairTableModel.getPairAt(modelRow);
        if (oldPair != null) {
          DifferentialPair updatedPair =
              new DifferentialPair(
                  oldPair.positiveNet(),
                  oldPair.negativeNet(),
                  oldPair.baseName(),
                  oldPair.coupledSpacing(),
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
      diffPairTableModel.refreshData(board);
    }
  }

  private void matchSelectedToLongest() {
    int[] selected = singleNetTable.getSelectedRows();
    if (selected.length < 2) {
      JOptionPane.showMessageDialog(
          this, "Please select two or more nets to match lengths together.");
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
      return;
    }

    double maxLenUser = toUser(board, maxLen);
    targetLengthField.setText(FMT.format(maxLenUser));
    applyRuleToSelected();
  }

  private void autoDetectDifferentialPairs() {
    RoutingBoard board = getBoard();
    if (board == null) {
      return;
    }
    int detected = diffPairTableModel.autoDetectPairs(board);
    JOptionPane.showMessageDialog(
        this,
        String.format(Locale.US, "Auto-detection identified %d new differential pairs.", detected),
        "Differential Pair Detection",
        JOptionPane.INFORMATION_MESSAGE);
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
    diffPairTableModel.refreshData(board);
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

    if (tabbedPane.getSelectedIndex() == 0) {
      // Tune length on selected nets
      applyRuleToSelected();
      app.freerouting.autoroute.tuning.PostRouteLengthTuner.TuningReport report =
          app.freerouting.autoroute.tuning.PostRouteLengthTuner.tune(board);

      refresh();
      boardFrame.repaint();
      JOptionPane.showMessageDialog(
          this,
          java.text.MessageFormat.format(
              tm.getText("tuning_complete_message"),
              report.totalMeandersInserted(),
              report.netsAttempted(),
              String.format(Locale.US, "%.2f", report.totalLengthAddedMm())),
          tm.getText("tuning_complete_title"),
          JOptionPane.INFORMATION_MESSAGE);
    } else {
      // Tune skew on selected pairs
      applyRuleToSelected();
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
    singleNetTableModel.refreshData(board);
    diffPairTableModel.refreshData(board);
    updateDiagram();
  }

  private RoutingBoard getBoard() {
    if (boardFrame != null
        && boardFrame.boardPanel != null
        && boardFrame.boardPanel.boardHandling != null) {
      return boardFrame.boardPanel.boardHandling.getRoutingBoard();
    }
    return null;
  }

  private static double toUser(RoutingBoard board, double boardUnits) {
    if (board != null
        && board.communication != null
        && board.communication.coordinateTransform != null) {
      return board.communication.coordinateTransform.boardToDsn(boardUnits);
    }
    return boardUnits;
  }

  private static double toBoardUnits(RoutingBoard board, double userUnits) {
    if (board != null
        && board.communication != null
        && board.communication.coordinateTransform != null) {
      return board.communication.coordinateTransform.dsnToBoard(userUnits);
    }
    return userUnits;
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
