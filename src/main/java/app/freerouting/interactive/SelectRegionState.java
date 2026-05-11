package app.freerouting.interactive;

import app.freerouting.geometry.planar.FloatPoint;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.geom.Point2D;

/**
 * Common base class for interactive selection of a rectangle.
 */
public class SelectRegionState extends InteractiveState {

  protected FloatPoint corner1;
  protected FloatPoint corner2;

  /**
   * Creates a new instance of SelectRegionState
   */
  protected SelectRegionState(InteractiveState p_parent_state, GuiBoardManager p_board_handling) {
    super(p_parent_state, p_board_handling);
  }

  @Override
  public InteractiveState button_released() {
    hdlg.screen_messages.set_status_message("");
    return complete();
  }

  @Override
  public InteractiveState mouse_dragged(FloatPoint p_point) {
    if (corner1 == null) {
      corner1 = p_point;
    }
    FloatPoint previous_corner2 = corner2;
    corner2 = p_point;
    repaint_selection_region(previous_corner2, corner2);
    return this;
  }

  @Override
  public void draw(Graphics p_graphics) {
    this.return_state.draw(p_graphics);
    if (corner1 == null || corner2 == null) {
      return;
    }
    hdlg.graphics_context.draw_rectangle(corner1, corner2, 1, Color.white, p_graphics, 1);
  }

  private void repaint_selection_region(FloatPoint previous_corner2, FloatPoint new_corner2) {
    Rectangle repaint_rectangle = get_screen_rectangle(corner1, new_corner2);
    if (repaint_rectangle == null) {
      return;
    }

    if (previous_corner2 != null) {
      Rectangle previous_rectangle = get_screen_rectangle(corner1, previous_corner2);
      if (previous_rectangle != null) {
        repaint_rectangle = repaint_rectangle.union(previous_rectangle);
      }
    }
    repaint_rectangle.grow(4, 4);
    hdlg.repaint(repaint_rectangle);
  }

  private Rectangle get_screen_rectangle(FloatPoint p_corner1, FloatPoint p_corner2) {
    if (p_corner1 == null || p_corner2 == null) {
      return null;
    }

    Point2D screen_corner1 = hdlg.graphics_context.coordinate_transform.board_to_screen(p_corner1);
    Point2D screen_corner2 = hdlg.graphics_context.coordinate_transform.board_to_screen(p_corner2);
    if (screen_corner1 == null || screen_corner2 == null) {
      return null;
    }

    int min_x = (int) Math.floor(Math.min(screen_corner1.getX(), screen_corner2.getX()));
    int min_y = (int) Math.floor(Math.min(screen_corner1.getY(), screen_corner2.getY()));
    int max_x = (int) Math.ceil(Math.max(screen_corner1.getX(), screen_corner2.getX()));
    int max_y = (int) Math.ceil(Math.max(screen_corner1.getY(), screen_corner2.getY()));
    return new Rectangle(min_x, min_y, Math.max(1, max_x - min_x), Math.max(1, max_y - min_y));
  }
}
