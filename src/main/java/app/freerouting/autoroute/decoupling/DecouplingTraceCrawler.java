package app.freerouting.autoroute.decoupling;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.trace.PolylineTrace;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Lightweight, thread-safe topological crawler that inspects direct surface trace connectivity
 * between pins on a single copper layer, ignoring flood fills (conduction areas) and layer
 * transitions.
 */
public final class DecouplingTraceCrawler {

  private DecouplingTraceCrawler() {}

  /**
   * Evaluates whether two pins are directly connected by an unbroken chain of {@link PolylineTrace}
   * items on the specified layer.
   *
   * @param board the board to inspect
   * @param pinAId ID of the starting pin
   * @param pinBId ID of the destination pin
   * @param netNumber electrical net number
   * @param layer copper layer to restrict the search to
   * @return true if a direct surface trace path exists between the pins on that layer
   */
  public static boolean hasDirectSurfaceTrace(
      BasicBoard board, int pinAId, int pinBId, int netNumber, int layer) {
    if (board == null || pinAId == pinBId) {
      return false;
    }

    Item itemA = board.getItem(pinAId);
    Item itemB = board.getItem(pinBId);
    if (!(itemA instanceof Pin pinA) || !(itemB instanceof Pin pinB)) {
      return false;
    }

    // Both pins must reside on the target layer
    if (pinA.firstLayer() > layer || pinA.lastLayer() < layer) {
      return false;
    }
    if (pinB.firstLayer() > layer || pinB.lastLayer() < layer) {
      return false;
    }

    Set<Item> visited = new HashSet<>();
    Queue<Item> queue = new ArrayDeque<>();
    queue.add(pinA);
    visited.add(pinA);

    while (!queue.isEmpty()) {
      Item current = queue.poll();
      if (current == pinB) {
        return true;
      }

      Collection<Item> contacts = current.getNormalContacts();
      if (contacts == null || contacts.isEmpty()) {
        continue;
      }

      for (Item contact : contacts) {
        if (contact == pinB) {
          return true;
        }
        if (contact instanceof PolylineTrace trace
            && trace.containsNet(netNumber)
            && trace.firstLayer() == layer
            && trace.lastLayer() == layer) {
          if (visited.add(trace)) {
            queue.add(trace);
          }
        }
      }
    }

    return false;
  }
}
