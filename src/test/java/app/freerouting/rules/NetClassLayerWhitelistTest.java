package app.freerouting.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import org.junit.jupiter.api.Test;

class NetClassLayerWhitelistTest {

  @Test
  void layerWhitelistIsConfigurablePerLayer() {
    LayerStructure layerStructure = new LayerStructure(new Layer[]{
        new Layer("L1", true),
        new Layer("L2", true),
        new Layer("L3", true),
        new Layer("L4", true)
    });
    ClearanceMatrix clearanceMatrix = ClearanceMatrix.get_default_instance(layerStructure, 100);
    NetClass netClass = new NetClass("class-A", layerStructure, clearanceMatrix, false);

    assertTrue(netClass.isLayerAllowed(0));
    assertTrue(netClass.isLayerAllowed(1));
    assertTrue(netClass.isLayerAllowed(2));
    assertTrue(netClass.isLayerAllowed(3));

    netClass.setLayerAllowed(1, false);
    netClass.setLayerAllowed(2, false);

    assertTrue(netClass.isLayerAllowed(0));
    assertFalse(netClass.isLayerAllowed(1));
    assertFalse(netClass.isLayerAllowed(2));
    assertTrue(netClass.isLayerAllowed(3));
  }
}
