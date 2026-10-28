package app.freerouting.board.model.items;

import app.freerouting.board.actions.ItemInfoPrinter;
import app.freerouting.board.actions.ItemSelectionFilter;
import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.searchtree.SearchTreeObject;
import app.freerouting.board.searchtree.ShapeSearchTree;
import app.freerouting.geometry.planar.Area;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.PolygonBooleanEngine;
import app.freerouting.geometry.planar.PolygonBooleanEngines;
import app.freerouting.geometry.planar.Shape;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.geometry.planar.Vector;
import app.freerouting.logger.FRLogger;
import app.freerouting.util.TextManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** A ObstacleArea, which can be electrically connected to other items. */
public class ConductionArea extends ObstacleArea implements Connectable {

  private static final double PLANE_HATCH_OPACITY = 0.85;

  private boolean isObstacle;
  private boolean isFilled = true;

  public boolean getIsFilled() {
    return this.isFilled;
  }

  /** SetIsFilled. */
  public void setIsFilled(boolean value) {
    this.isFilled = value;
    this.clearDerivedData();
  }

  private transient int cachedBoardRevision = -1;
  private transient PolygonBooleanEngine.PolygonShape cachedBoardFillPolygon;

  /** Creates a new instance of ConductionArea. */
  public ConductionArea(
      Area area,
      int layer,
      Vector translation,
      double rotationInDegree,
      boolean sideChanged,
      int[] netNumbers,
      int clearanceClassIndex,
      int id,
      int groupId,
      String name,
      boolean isObstacle,
      FixedState fixedState,
      BasicBoard board) {
    super(
        area,
        layer,
        translation,
        rotationInDegree,
        sideChanged,
        netNumbers,
        clearanceClassIndex,
        id,
        groupId,
        name,
        fixedState,
        board);
    this.isObstacle = isObstacle;
  }

  @Override
  public void clearDerivedData() {
    super.clearDerivedData();
    this.cachedBoardRevision = -1;
    this.cachedBoardFillPolygon = null;
  }

  /** Pre-computes detailed plane-fill geometry off the EDT so zoom-in paints stay responsive. */
  public void warmDetailedFillCache() {
    if (!this.isFilled) {
      return;
    }
    int layerIndex = this.getLayer();
    double maxClearanceLookupBoard = 2000.0 * this.board.communication.getResolution(Unit.UM);
    if (this.board.rules != null && this.board.rules.clearanceMatrix != null) {
      double maxMatrixClearance =
          this.board.rules.clearanceMatrix.maxValue(this.clearanceClassIndex(), layerIndex);
      maxClearanceLookupBoard =
          Math.max(
              maxClearanceLookupBoard,
              maxMatrixClearance + 100.0 * this.board.communication.getResolution(Unit.UM));
    }
    ensureDetailedFillCache(maxClearanceLookupBoard, layerIndex);
  }

  /**
   * Returns the detailed fill polygonal geometry for this conduction area, computing clearances
   * against all overlapping items if needed.
   */
  public PolygonBooleanEngine.PolygonShape getDetailedFillPolygon() {
    warmDetailedFillCache();
    return cachedBoardFillPolygon;
  }

  /** Returns the detailed fill geometry for this conduction area as an AWT Area. */
  public java.awt.geom.Area getDetailedFillArea() {
    PolygonBooleanEngine.PolygonShape poly = getDetailedFillPolygon();
    return poly != null && poly.unwrap() instanceof java.awt.geom.Area a ? a : null;
  }

  /** Returns the cached detailed fill geometry for renderer-owned painting. */
  public PolygonBooleanEngine.PolygonShape getDetailedFillPolygon(
      double maxClearanceLookupBoard, int layerIndex) {
    ensureDetailedFillCache(maxClearanceLookupBoard, layerIndex);
    return cachedBoardFillPolygon;
  }

  /** Returns the cached detailed fill geometry for renderer-owned painting as an AWT Area. */
  public java.awt.geom.Area getDetailedFillArea(double maxClearanceLookupBoard, int layerIndex) {
    PolygonBooleanEngine.PolygonShape poly =
        getDetailedFillPolygon(maxClearanceLookupBoard, layerIndex);
    return poly != null && poly.unwrap() instanceof java.awt.geom.Area a ? a : null;
  }

  private void ensureDetailedFillCache(double maxClearanceLookupBoard, int layerIndex) {
    boolean boardChanged = this.board.getRevision() != cachedBoardRevision;
    if (cachedBoardFillPolygon != null && !boardChanged) {
      return;
    }

    PolygonBooleanEngine engine = PolygonBooleanEngines.get();
    PolygonBooleanEngine.PolygonShape fillShape =
        PolygonBooleanEngines.toPolygonShape(engine, this.getArea());
    if (fillShape == null) {
      fillShape = engine.createEmpty();
    }
    if (!fillShape.isEmpty()) {
      IntBox bbox = this.boundingBox();
      double spokeWidth = 400.0 * this.board.communication.getResolution(Unit.UM);
      int maxCl = (int) Math.round(maxClearanceLookupBoard);
      IntBox inflatedBbox =
          new IntBox(
              new IntPoint(bbox.ll.x - maxCl, bbox.ll.y - maxCl),
              new IntPoint(bbox.ur.x + maxCl, bbox.ur.y + maxCl));

      List<PolygonBooleanEngine.PolygonShape> foreignClearances = new ArrayList<>();
      List<PolygonBooleanEngine.PolygonShape> sameNetClearances = new ArrayList<>();
      List<PolygonBooleanEngine.PolygonShape> sameNetSpokesList = new ArrayList<>();

      Set<SearchTreeObject> overlaps = this.board.overlappingObjects(inflatedBbox, layerIndex);
      for (SearchTreeObject ob : overlaps) {
        if (!(ob instanceof Item currentItem) || currentItem == this) {
          continue;
        }
        if (!currentItem.sharesLayer(this)) {
          continue;
        }

        if (currentItem instanceof Trace || currentItem instanceof ConductionArea) {
          if (currentItem.sharesNet(this)) {
            continue;
          }
        }

        int clClass1 = this.clearanceClassIndex();
        int clClass2 = currentItem.clearanceClassIndex();
        double clearanceDist = this.board.clearanceValue(clClass1, clClass2, layerIndex);

        if (currentItem.sharesNet(this)) {
          if (currentItem instanceof DrillItem drillItem) {
            FloatPoint center = drillItem.getCenter().toFloat();
            Shape shape = drillItem.getShapeOnLayer(layerIndex);
            if (shape == null) {
              continue;
            }

            Shape enlargedShape = shape.enlarge(clearanceDist);
            PolygonBooleanEngine.PolygonShape clearancePoly =
                PolygonBooleanEngines.toPolygonShape(engine, enlargedShape);
            if (clearancePoly == null || clearancePoly.isEmpty()) {
              continue;
            }

            IntBox itemBbox = drillItem.boundingBox();
            double maxDim = Math.max(itemBbox.width(), itemBbox.height());
            double expansionRadiusBoard = (maxDim / 2.0) + clearanceDist;

            PolygonBooleanEngine.PolygonShape spokes =
                engine.createThermalSpokes(center.x, center.y, spokeWidth, expansionRadiusBoard);
            spokes = engine.intersect(spokes, clearancePoly);

            sameNetClearances.add(clearancePoly);
            sameNetSpokesList.add(spokes);
          }
        } else {
          if (currentItem instanceof DrillItem drillItem) {
            Shape shape = drillItem.getShapeOnLayer(layerIndex);
            if (shape != null) {
              Shape enlargedShape = shape.enlarge(clearanceDist);
              PolygonBooleanEngine.PolygonShape clearancePoly =
                  PolygonBooleanEngines.toPolygonShape(engine, enlargedShape);
              if (clearancePoly != null && !clearancePoly.isEmpty()) {
                foreignClearances.add(clearancePoly);
              }
            }
          } else {
            int shapeCount = currentItem.tileShapeCount();
            for (int i = 0; i < shapeCount; i++) {
              if (currentItem.shapeLayer(i) == layerIndex) {
                TileShape tileShape = currentItem.getTileShape(i);
                if (tileShape != null) {
                  Shape enlargedShape = tileShape.enlarge(clearanceDist);
                  PolygonBooleanEngine.PolygonShape clearancePoly =
                      PolygonBooleanEngines.toPolygonShape(engine, enlargedShape);
                  if (clearancePoly != null && !clearancePoly.isEmpty()) {
                    foreignClearances.add(clearancePoly);
                  }
                }
              }
            }
          }
        }
      }

      fillShape = engine.subtractAll(fillShape, foreignClearances);
      fillShape = engine.subtractAll(fillShape, sameNetClearances);
      if (!sameNetSpokesList.isEmpty()) {
        fillShape = engine.union(fillShape, engine.unionAll(sameNetSpokesList));
      }
    }
    cachedBoardFillPolygon = fillShape;
    cachedBoardRevision = this.board.getRevision();
  }

  @Override
  public Item copy(int id) {
    return new ConductionArea(
        getRelativeArea(),
        getLayer(),
        getTranslation(),
        getRotationInDegree(),
        getSideChanged(),
        netNumbers,
        clearanceClassIndex(),
        id,
        getComponentId(),
        this.name,
        isObstacle,
        getFixedState(),
        board);
  }

  @Override
  public Set<Item> getNormalContacts() {
    Set<Item> result = new TreeSet<>();
    for (int i = 0; i < tileShapeCount(); i++) {
      TileShape currentShape = getTileShape(i);
      Set<SearchTreeObject> overlaps = board.overlappingObjects(currentShape, getLayer());
      for (SearchTreeObject currentObject : overlaps) {
        if (!(currentObject instanceof Item currentItem)) {
          continue;
        }
        if (currentItem != this && currentItem.sharesNet(this) && currentItem.sharesLayer(this)) {
          if (currentItem instanceof Trace currentTrace) {
            if (currentShape.contains(currentTrace.firstCorner())
                || currentShape.contains(currentTrace.lastCorner())) {
              result.add(currentItem);
            }
          } else if (currentItem instanceof DrillItem currentDrillItem) {
            if (currentShape.contains(currentDrillItem.getCenter())) {
              result.add(currentItem);
            }
          }
        }
      }
    }
    return result;
  }

  @Override
  public TileShape getTraceConnectionShape(ShapeSearchTree searchTree, int index) {
    if (index < 0 || index >= this.treeShapeCount(searchTree)) {
      FRLogger.warn("ConductionArea.get_trace_connection_shape index out of range");
      return null;
    }
    return this.getTreeShape(searchTree, index);
  }

  @Override
  public Point[] getRatsnestCorners() {
    Point[] result;
    FloatPoint[] corners = this.getArea().cornerApproxArr();
    result = new Point[corners.length];
    for (int i = 0; i < corners.length; i++) {
      result[i] = corners[i].round();
    }

    return result;
  }

  @Override
  public boolean isObstacle(Item other) {
    if (this.isObstacle) {
      return super.isObstacle(other);
    }
    return false;
  }

  /** Returns if this conduction area is regarded as obstacle to traces of foreign nets. */
  public boolean getIsObstacle() {
    return this.isObstacle;
  }

  /** Sets, if this conduction area is regarded as obstacle to traces and vias of foreign nets. */
  public void setIsObstacle(boolean value) {
    this.isObstacle = value;
  }

  @Override
  public boolean isTraceObstacle(int netNumber) {
    return this.isObstacle && !this.containsNet(netNumber);
  }

  @Override
  public boolean isDrillable(int netNumber) {
    return !this.isObstacle || this.containsNet(netNumber);
  }

  @Override
  public boolean isSelectedByFilter(ItemSelectionFilter filter) {
    if (!this.isSelectedByFixedFilter(filter)) {
      return false;
    }
    return filter.isSelected(ItemSelectionFilter.SelectableChoices.CONDUCTION);
  }

  @Override
  public void printInfo(ItemInfoPrinter printer, Locale locale) {
    TextManager tm = new TextManager(this.getClass(), locale);

    printer.appendBold(tm.getText("conductionArea"));
    this.printShapeInfo(printer, locale);
    this.printConnectableItemInfo(printer, locale);
    printer.newline();
  }
}
