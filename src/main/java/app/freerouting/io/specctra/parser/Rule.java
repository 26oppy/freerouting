package app.freerouting.io.specctra.parser;

import app.freerouting.datastructures.IdentifierType;
import app.freerouting.datastructures.IndentFileWriter;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.BoardRules;
import app.freerouting.rules.ClearanceMatrix;
import app.freerouting.rules.NetClass;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/** Class for reading and writing rule scopes from dsn-files. */
@SuppressWarnings({
  "checkstyle:MissingJavadocMethod",
  "checkstyle:MissingJavadocType",
  "checkstyle:VariableDeclarationUsageDistance"
})
public abstract class Rule {

  /** Returns a collection of objects of class Rule. */
  public static Collection<Rule> readScope(IJFlexScanner scanner) {
    Collection<Rule> result = new LinkedList<>();
    Object currentToken = null;
    for (; ; ) {
      Object prevToken = currentToken;
      try {
        currentToken = scanner.nextToken();
      } catch (IOException e) {
        FRLogger.error("Rule.read_scope: IO error scanning file", e);
        return null;
      }
      if (currentToken == null) {
        FRLogger.warn(
            "Rule.read_scope: unexpected end of file at '" + scanner.getScopeIdentifier() + "'");
        return null;
      }
      if (currentToken == Keyword.CLOSED_BRACKET) {
        // end of scope
        break;
      }

      if (prevToken == Keyword.OPEN_BRACKET) {
        // every rule starts with a "("
        Rule currentRule = null;
        if (currentToken == Keyword.WIDTH) {
          // this is a "(width" rule
          currentRule = readWidthRule(scanner);
        } else if (currentToken == Keyword.CLEARANCE) {
          // this is a "(clear" rule
          currentRule = readClearanceRule(scanner);
        } else if (currentToken == Keyword.LENGTH) {
          // this is a "(length" rule
          Circuit.LengthMatchingRule lengthResult = Circuit.readLengthScope(scanner);
          if (lengthResult != null) {
            currentRule = new LengthRule(lengthResult.maxLength, lengthResult.minLength);
          }
        } else if (currentToken == Keyword.MEANDER) {
          currentRule = readMeanderRule(scanner);
        } else if (currentToken == Keyword.LENGTH_AMPLITUDE) {
          double maxAmp = scanner.nextDouble();
          double minAmp = scanner.nextDouble();
          if (scanner.nextClosingBracket()) {
            currentRule = new MeanderRule("single_track", maxAmp, minAmp, -1.0, null, null, null);
          }
        } else if (currentToken == Keyword.LENGTH_GAP) {
          double gap = scanner.nextDouble();
          if (scanner.nextClosingBracket()) {
            currentRule = new MeanderRule("single_track", -1.0, -1.0, gap, null, null, null);
          }
        } else if (currentToken == Keyword.SINGLE_SIDED) {
          String sideStr = scanner.nextString();
          Boolean singleSided = "on".equalsIgnoreCase(sideStr);
          if (scanner.nextClosingBracket()) {
            currentRule =
                new MeanderRule("single_track", -1.0, -1.0, -1.0, singleSided, null, null);
          }
        } else if (currentToken == Keyword.CORNER_STYLE) {
          String style = scanner.nextString();
          Integer radius = null;
          try {
            Object nextObj = scanner.nextToken();
            if (nextObj instanceof Double d) {
              radius = d.intValue();
              scanner.nextClosingBracket();
            } else if (nextObj == Keyword.CLOSED_BRACKET) {
              // Closed directly
            }
          } catch (IOException e) {
            FRLogger.error("Rule.readScope: IO error scanning file", e);
            return null;
          }
          currentRule = new MeanderRule("single_track", -1.0, -1.0, -1.0, null, style, radius);
        } else {
          ScopeKeyword.skipScope(scanner);
        }

        if (currentRule != null) {
          result.add(currentRule);
        }
      }
    }
    return result;
  }

  /** Reads a LayerRule from dsn-file. */
  public static LayerRule readLayerRuleScope(IJFlexScanner scanner) {
    try {
      Collection<String> layerNames = new LinkedList<>();
      Collection<Rule> ruleList = new LinkedList<>();
      for (; ; ) {
        scanner.yybegin(SpecctraDsnStreamReader.LAYER_NAME);
        Object nextToken = scanner.nextToken();
        if (nextToken == Keyword.OPEN_BRACKET) {
          break;
        }
        if (!(nextToken instanceof String)) {

          FRLogger.warn(
              "Rule.read_layer_rule_scope: string expected at '"
                  + scanner.getScopeIdentifier()
                  + "'");
          return null;
        }
        layerNames.add((String) nextToken);
      }
      for (; ; ) {
        Object nextToken = scanner.nextToken();
        if (nextToken == Keyword.CLOSED_BRACKET) {
          break;
        }
        if (nextToken != Keyword.RULE) {

          FRLogger.warn(
              "Rule.read_layer_rule_scope: rule expected at '"
                  + scanner.getScopeIdentifier()
                  + "'");
          return null;
        }
        ruleList.addAll(readScope(scanner));
      }
      return new LayerRule(layerNames, ruleList);
    } catch (IOException e) {
      FRLogger.error("Rule.read_layer_rule_scope: IO error scanning file", e);
      return null;
    }
  }

  public static WidthRule readWidthRule(IJFlexScanner scanner) {
    double value = scanner.nextDouble();

    if (!scanner.nextClosingBracket()) {
      return null;
    }

    return new WidthRule(value);
  }

  public static void writeScope(NetClass netClass, WriteScopeParameter scopeParameter)
      throws IOException {
    scopeParameter.file.startScope();
    scopeParameter.file.write("rule");

    // write the trace width
    int defaultTraceHalfWidth = netClass.getTraceHalfWidth(0);
    double traceWidth = 2 * scopeParameter.coordinateTransform.boardToDsn(defaultTraceHalfWidth);
    scopeParameter.file.newLine();
    scopeParameter.file.write("(width ");
    scopeParameter.file.write(String.valueOf(traceWidth));
    scopeParameter.file.write(")");

    writeMeanderConstraint(scopeParameter, netClass.singleTrackMeander, "single_track");
    writeMeanderConstraint(scopeParameter, netClass.diffPairMeander, "diff_pair");
    writeMeanderConstraint(scopeParameter, netClass.diffPairSkewMeander, "diff_pair_skew");

    scopeParameter.file.endScope();
    for (int i = 1; i < scopeParameter.board.layerStructure.layers.length; i++) {
      if (netClass.getTraceHalfWidth(i) != defaultTraceHalfWidth) {
        writeLayerRule(netClass, i, scopeParameter);
      }
    }
  }

  private static void writeLayerRule(
      NetClass netClass, int layerIndex, WriteScopeParameter scopeParameter) throws IOException {
    scopeParameter.file.startScope();
    scopeParameter.file.write("layer_rule ");

    app.freerouting.board.model.structure.Layer currentBoardLayer =
        scopeParameter.board.layerStructure.layers[layerIndex];

    scopeParameter.file.write(currentBoardLayer.name);
    scopeParameter.file.startScope();
    scopeParameter.file.write("rule ");

    int currentTraceHalfWidth = netClass.getTraceHalfWidth(layerIndex);

    // write the trace width
    double traceWidth = 2 * scopeParameter.coordinateTransform.boardToDsn(currentTraceHalfWidth);
    scopeParameter.file.newLine();
    scopeParameter.file.write("(width ");
    scopeParameter.file.write(String.valueOf(traceWidth));
    scopeParameter.file.write(") ");
    scopeParameter.file.endScope();
    scopeParameter.file.endScope();
  }

  /** Writes the default rule as a scope to an output dsn-file. */
  public static void writeDefaultRule(WriteScopeParameter scopeParameter, int layer)
      throws IOException {
    scopeParameter.file.startScope();
    scopeParameter.file.write("rule");
    // write the trace width
    double traceWidth =
        2
            * scopeParameter.coordinateTransform.boardToDsn(
                scopeParameter.board.rules.getDefaultNetClass().getTraceHalfWidth(0));
    scopeParameter.file.newLine();
    scopeParameter.file.write("(width ");
    scopeParameter.file.write(String.valueOf(traceWidth));
    scopeParameter.file.write(")");
    // write the default clearance rule
    int defaultClNo = BoardRules.defaultClearanceClass();
    int defaultBoardClearance =
        scopeParameter.board.rules.clearanceMatrix.getValue(defaultClNo, defaultClNo, layer, false);
    double defaultClearance = scopeParameter.coordinateTransform.boardToDsn(defaultBoardClearance);
    scopeParameter.file.newLine();
    // write the default clearance
    scopeParameter.file.write("(clearance ");
    scopeParameter.file.write(String.valueOf(defaultClearance));
    scopeParameter.file.write(")");
    // write the smd_to_turn_gap
    double smdToTurnDist =
        scopeParameter.coordinateTransform.boardToDsn(
            scopeParameter.board.rules.getPinEdgeToTurnDist());
    scopeParameter.file.newLine();
    scopeParameter.file.write("(clearance ");
    scopeParameter.file.write(String.valueOf(smdToTurnDist));
    scopeParameter.file.write(" (type smd_to_turn_gap))");

    // write the named clearance rules from the clearance matrix
    writeNamedClearanceRules(scopeParameter, layer);
    // write_non_default_clearance_rules(scopeParameter, layer, defaultBoardClearance);

    writeMeanderConstraint(
        scopeParameter, scopeParameter.board.rules.singleTrackMeander, "single_track");
    writeMeanderConstraint(scopeParameter, scopeParameter.board.rules.diffPairMeander, "diff_pair");
    writeMeanderConstraint(
        scopeParameter, scopeParameter.board.rules.diffPairSkewMeander, "diff_pair_skew");

    scopeParameter.file.endScope();
  }

  public static void writeMeanderConstraint(
      WriteScopeParameter scopeParameter,
      app.freerouting.rules.NetMeanderConstraint constraint,
      String target)
      throws IOException {
    if (constraint == null) return;
    scopeParameter.file.startScope();
    scopeParameter.file.write("meander ");
    scopeParameter.file.write(target);

    if (constraint.maxAmplitude() != null || constraint.minAmplitude() != null) {
      scopeParameter.file.newLine();
      scopeParameter.file.write("(length_amplitude ");
      double max =
          constraint.maxAmplitude() != null
              ? scopeParameter.coordinateTransform.boardToDsn(constraint.maxAmplitude())
              : -1.0;
      double min =
          constraint.minAmplitude() != null
              ? scopeParameter.coordinateTransform.boardToDsn(constraint.minAmplitude())
              : -1.0;
      scopeParameter.file.write(String.valueOf(max));
      scopeParameter.file.write(" ");
      scopeParameter.file.write(String.valueOf(min));
      scopeParameter.file.write(")");
    }
    if (constraint.gap() != null) {
      scopeParameter.file.newLine();
      scopeParameter.file.write("(length_gap ");
      scopeParameter.file.write(
          String.valueOf(scopeParameter.coordinateTransform.boardToDsn(constraint.gap())));
      scopeParameter.file.write(")");
    }
    if (constraint.singleSided() != null) {
      scopeParameter.file.newLine();
      scopeParameter.file.write("(single_sided ");
      scopeParameter.file.write(constraint.singleSided() ? "on" : "off");
      scopeParameter.file.write(")");
    }
    if (constraint.cornerStyle() != null || constraint.cornerRadiusPercentage() != null) {
      scopeParameter.file.newLine();
      scopeParameter.file.write("(corner_style ");
      scopeParameter.file.write(
          constraint.cornerStyle() != null ? constraint.cornerStyle().toDsn() : "auto");
      if (constraint.cornerRadiusPercentage() != null) {
        scopeParameter.file.write(" ");
        scopeParameter.file.write(String.valueOf(constraint.cornerRadiusPercentage()));
      }
      scopeParameter.file.write(")");
    }
    scopeParameter.file.endScope();
  }

  /** Write the clearance rules, which are different from the default clearance. */
  private static void writeNonDefaultClearanceRules(
      WriteScopeParameter scopeParameter, int layer, int defaultClearance) throws IOException {

    ClearanceMatrix clMatrix = scopeParameter.board.rules.clearanceMatrix;
    int clCount = scopeParameter.board.rules.clearanceMatrix.getClassCount();

    for (int i = 1; i <= clCount; i++) {
      for (int j = i; j < clCount; j++) {
        int currentBoardClearance = clMatrix.getValue(i, j, layer, false);

        if (currentBoardClearance == defaultClearance) {
          continue;
        }

        double currentClearance =
            scopeParameter.coordinateTransform.boardToDsn(currentBoardClearance);
        scopeParameter.file.newLine();
        scopeParameter.file.write("(clearance ");
        scopeParameter.file.write(String.valueOf(currentClearance));
        scopeParameter.file.write(" (type ");
        scopeParameter.identifierType.write(clMatrix.getName(i), scopeParameter.file);
        scopeParameter.file.write(DsnFile.CLASS_CLEARANCE_SEPARATOR);
        scopeParameter.identifierType.write(clMatrix.getName(j), scopeParameter.file);
        scopeParameter.file.write("))");
      }
    }
  }

  /** Write the clearance rules for the named classes in the clearance matrix. */
  private static void writeNamedClearanceRules(WriteScopeParameter scopeParameter, int layer)
      throws IOException {

    ClearanceMatrix clMatrix = scopeParameter.board.rules.clearanceMatrix;
    int clCount = scopeParameter.board.rules.clearanceMatrix.getClassCount();

    for (int i = 1; i < clCount; i++) {
      if (Objects.equals(clMatrix.getName(i), "default")) {
        continue;
      }

      int currentBoardClearance = clMatrix.getValue(i, i, layer, false);
      double currentClearance =
          scopeParameter.coordinateTransform.boardToDsn(currentBoardClearance);

      scopeParameter.file.newLine();
      scopeParameter.file.write("(clearance ");
      scopeParameter.file.write(String.valueOf(currentClearance));
      scopeParameter.file.write(" (type ");
      scopeParameter.identifierType.write(clMatrix.getName(i), scopeParameter.file);
      scopeParameter.file.write("))");
    }
  }

  public static ClearanceRule readClearanceRule(IJFlexScanner scanner) {
    try {
      double value = scanner.nextDouble();

      Collection<String> classPairs = new LinkedList<>();
      Object nextToken = scanner.nextToken();
      if (nextToken != Keyword.CLOSED_BRACKET) {
        // look for "(type"
        if (nextToken != Keyword.OPEN_BRACKET) {
          FRLogger.warn(
              "Rule.read_clearance_rule: ( expected at '" + scanner.getScopeIdentifier() + "'");
          return null;
        }
        nextToken = scanner.nextToken();
        if (nextToken != Keyword.TYPE) {
          FRLogger.warn(
              "Rule.read_clearance_rule: type expected at '" + scanner.getScopeIdentifier() + "'");
          return null;
        }

        classPairs.addAll(List.of(scanner.nextStringList(DsnFile.CLASS_CLEARANCE_SEPARATOR)));

        // check the closing ")" of "(type"
        if (!scanner.nextClosingBracket()) {
          FRLogger.warn(
              "Rule.read_clearance_rule: closing bracket expected at '"
                  + scanner.getScopeIdentifier()
                  + "'");
          return null;
        }

        // check the closing ")" of "(clear"
        if (!scanner.nextClosingBracket()) {
          FRLogger.warn(
              "Rule.read_clearance_rule: closing bracket expected at '"
                  + scanner.getScopeIdentifier()
                  + "'");
          return null;
        }
      }

      return new ClearanceRule(value, classPairs);
    } catch (IOException e) {
      FRLogger.error("Rule.read_clearance_rule: IO error scanning file", e);
      return null;
    }
  }

  public static void writeItemClearanceClass(
      String name, IndentFileWriter file, IdentifierType identifierType) throws IOException {
    file.newLine();
    file.write("(clearance_class ");
    identifierType.write(name, file);
    file.write(")");
  }

  public static class WidthRule extends Rule {

    public final double value;

    public WidthRule(double value) {
      this.value = value;
    }
  }

  public static class ClearanceRule extends Rule {

    final double value;
    final Collection<String> clearanceClassPairs;

    public ClearanceRule(double value, Collection<String> classPairs) {
      this.value = value;
      clearanceClassPairs = classPairs;
    }
  }

  public static class LengthRule extends Rule {

    public final double maxLength;
    public final double minLength;

    public LengthRule(double maxLength, double minLength) {
      this.maxLength = maxLength;
      this.minLength = minLength;
    }
  }

  public static MeanderRule readMeanderRule(IJFlexScanner scanner) {
    try {
      String target = scanner.nextString();
      double maxAmp = -1.0;
      double minAmp = -1.0;
      double gap = -1.0;
      Boolean singleSided = null;
      String cornerStyle = null;
      Integer cornerRadius = null;

      for (; ; ) {
        Object nextToken = scanner.nextToken();
        if (nextToken == Keyword.CLOSED_BRACKET) {
          break;
        }
        if (nextToken == Keyword.OPEN_BRACKET) {
          Object propToken = scanner.nextToken();
          if (propToken == Keyword.LENGTH_AMPLITUDE) {
            maxAmp = scanner.nextDouble();
            minAmp = scanner.nextDouble();
            scanner.nextClosingBracket();
          } else if (propToken == Keyword.LENGTH_GAP) {
            gap = scanner.nextDouble();
            scanner.nextClosingBracket();
          } else if (propToken == Keyword.SINGLE_SIDED) {
            String sideStr = scanner.nextString();
            singleSided = "on".equalsIgnoreCase(sideStr);
            scanner.nextClosingBracket();
          } else if (propToken == Keyword.CORNER_STYLE) {
            cornerStyle = scanner.nextString();
            Object rObj = scanner.nextToken();
            if (rObj instanceof Double d) {
              cornerRadius = d.intValue();
              scanner.nextClosingBracket();
            } else if (rObj == Keyword.CLOSED_BRACKET) {
              // no radius
            } else {
              scanner.nextClosingBracket();
            }
          } else {
            ScopeKeyword.skipScope(scanner);
          }
        } else {
          FRLogger.warn("Rule.readMeanderRule: ( expected");
          return null;
        }
      }
      return new MeanderRule(target, maxAmp, minAmp, gap, singleSided, cornerStyle, cornerRadius);
    } catch (IOException e) {
      FRLogger.error("Rule.readMeanderRule: IO error scanning file", e);
      return null;
    }
  }

  public static class MeanderRule extends Rule {
    public final String target;
    public final double maxAmplitude;
    public final double minAmplitude;
    public final double gap;
    public final Boolean singleSided;
    public final String cornerStyle;
    public final Integer cornerRadius;

    public MeanderRule(
        String target,
        double maxAmplitude,
        double minAmplitude,
        double gap,
        Boolean singleSided,
        String cornerStyle,
        Integer cornerRadius) {
      this.target = target;
      this.maxAmplitude = maxAmplitude;
      this.minAmplitude = minAmplitude;
      this.gap = gap;
      this.singleSided = singleSided;
      this.cornerStyle = cornerStyle;
      this.cornerRadius = cornerRadius;
    }
  }

  public static class LayerRule {

    final Collection<String> layerNames;
    final Collection<Rule> rules;

    LayerRule(Collection<String> layerNames, Collection<Rule> rules) {
      this.layerNames = layerNames;
      this.rules = rules;
    }
  }
}
