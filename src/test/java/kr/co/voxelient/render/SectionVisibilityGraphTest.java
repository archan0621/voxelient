package kr.co.voxelient.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionVisibilityGraphTest {

    @Test
    void computeVisibility_ShouldTreatSparseSectionsAsFullyVisible() {
        SectionVisibility visibility = new SectionVisibilityGraph().computeVisibility();

        assertTrue(visibility.isVisible(SectionFace.NORTH, SectionFace.SOUTH));
        assertTrue(visibility.isVisible(SectionFace.WEST, SectionFace.EAST));
        assertTrue(visibility.isVisible(SectionFace.DOWN, SectionFace.UP));
    }

    @Test
    void computeVisibility_ShouldTreatFullSectionsAsNotPassable() {
        SectionVisibilityGraph graph = new SectionVisibilityGraph();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    graph.setOpaque(x, y, z);
                }
            }
        }

        SectionVisibility visibility = graph.computeVisibility();

        assertFalse(visibility.isVisible(SectionFace.NORTH, SectionFace.SOUTH));
        assertFalse(visibility.isVisible(SectionFace.WEST, SectionFace.EAST));
        assertFalse(visibility.isVisible(SectionFace.DOWN, SectionFace.UP));
    }

    @Test
    void computeVisibility_ShouldBlockFacesSeparatedByOpaqueWall() {
        SectionVisibilityGraph graph = new SectionVisibilityGraph();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                graph.setOpaque(8, y, z);
            }
        }

        SectionVisibility visibility = graph.computeVisibility();

        assertFalse(visibility.isVisible(SectionFace.WEST, SectionFace.EAST));
        assertTrue(visibility.isVisible(SectionFace.NORTH, SectionFace.SOUTH));
    }
}
