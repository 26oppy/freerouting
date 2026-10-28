package app.freerouting.drc;

import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.geometry.planar.AwtPolygonBooleanEngine;
import app.freerouting.geometry.planar.PolygonBooleanEngine;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a DRC violation where a copper pour (conduction area) is fragmented into disconnected
 * islands, causing isolated pins or floating dead copper.
 */
public class ZoneIslandViolation {

  /** The parent conduction area (copper pour) that contains this island. */
  public final ConductionArea conductionArea;

  /** The isolated polygonal island geometry in board units. */
  public final Area islandArea;

  /** The isolated polygonal island shape representation. */
  public final PolygonBooleanEngine.PolygonShape islandPolygon;

  /** The board layer index where this island resides. */
  public final int layer;

  /** The net number associated with this copper pour. */
  public final int netNumber;

  /**
   * The violation classification:
   *
   * <ul>
   *   <li>{@code "isolated_island_unconnected"} - The island contains pins or vias that are
   *       disconnected from the primary connected set of the net.
   *   <li>{@code "isolated_island_dead_copper"} - The island has no electrical contact items and
   *       represents floating dead copper.
   * </ul>
   */
  public final String type;

  /** Items (e.g. pins or vias) located within this island. */
  public final List<Item> itemsInIsland;

  /**
   * Creates a new zone island violation with a polygon shape.
   *
   * @param conductionArea the parent conduction area
   * @param islandPolygon the disconnected island polygon shape
   * @param layer board layer index
   * @param netNumber net number
   * @param type violation type descriptor
   * @param itemsInIsland items residing inside this island
   */
  public ZoneIslandViolation(
      ConductionArea conductionArea,
      PolygonBooleanEngine.PolygonShape islandPolygon,
      int layer,
      int netNumber,
      String type,
      List<Item> itemsInIsland) {
    this.conductionArea = conductionArea;
    this.islandPolygon = islandPolygon;
    this.islandArea = islandPolygon != null && islandPolygon.unwrap() instanceof Area a ? a : null;
    this.layer = layer;
    this.netNumber = netNumber;
    this.type = type;
    this.itemsInIsland =
        itemsInIsland != null
            ? Collections.unmodifiableList(new ArrayList<>(itemsInIsland))
            : Collections.emptyList();
  }

  /**
   * Creates a new zone island violation with an AWT Area.
   *
   * @param conductionArea the parent conduction area
   * @param islandArea the disconnected island shape
   * @param layer board layer index
   * @param netNumber net number
   * @param type violation type descriptor
   * @param itemsInIsland items residing inside this island
   */
  public ZoneIslandViolation(
      ConductionArea conductionArea,
      Area islandArea,
      int layer,
      int netNumber,
      String type,
      List<Item> itemsInIsland) {
    this(
        conductionArea,
        islandArea != null ? new AwtPolygonBooleanEngine.AwtPolygonShape(islandArea) : null,
        layer,
        netNumber,
        type,
        itemsInIsland);
  }

  /** Returns the center of the bounding box of this island in board units. */
  public double[] getCenter() {
    if (islandArea != null && !islandArea.isEmpty()) {
      Rectangle2D bounds = islandArea.getBounds2D();
      return new double[] {bounds.getCenterX(), bounds.getCenterY()};
    }
    if (islandPolygon != null && !islandPolygon.isEmpty()) {
      return new double[] {
        islandPolygon.getBoundsWidth() / 2.0, islandPolygon.getBoundsHeight() / 2.0
      };
    }
    return new double[] {0.0, 0.0};
  }

  /** Returns the approximate area in square board units. */
  public double getAreaInBoardUnits() {
    if (islandArea != null && !islandArea.isEmpty()) {
      Rectangle2D bounds = islandArea.getBounds2D();
      return bounds.getWidth() * bounds.getHeight();
    }
    if (islandPolygon != null && !islandPolygon.isEmpty()) {
      return islandPolygon.getBoundsWidth() * islandPolygon.getBoundsHeight();
    }
    return 0.0;
  }
}
