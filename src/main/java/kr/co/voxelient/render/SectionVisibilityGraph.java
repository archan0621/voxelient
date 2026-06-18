package kr.co.voxelient.render;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

/**
 * Minecraft-style visibility graph for one 16x16x16 render section.
 */
public final class SectionVisibilityGraph {
    private static final int SECTION_SIZE = 16;
    private static final int CELL_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int SPARSE_OPAQUE_THRESHOLD = 256;

    private final BitSet opaque = new BitSet(CELL_COUNT);
    private int opaqueCount = 0;

    public void setOpaque(int localX, int localY, int localZ) {
        if (!isInside(localX, localY, localZ)) {
            return;
        }

        int index = index(localX, localY, localZ);
        if (!opaque.get(index)) {
            opaque.set(index);
            opaqueCount++;
        }
    }

    public SectionVisibility computeVisibility() {
        SectionVisibility visibility = new SectionVisibility();
        if (opaqueCount < SPARSE_OPAQUE_THRESHOLD) {
            visibility.setAllVisible(true);
            return visibility;
        }
        if (opaqueCount == CELL_COUNT) {
            visibility.setAllVisible(false);
            return visibility;
        }

        BitSet visited = (BitSet) opaque.clone();
        for (int z = 0; z < SECTION_SIZE; z++) {
            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    if (!isBoundary(x, y, z)) {
                        continue;
                    }

                    int start = index(x, y, z);
                    if (!visited.get(start)) {
                        visibility.setManyVisible(floodFill(start, visited));
                    }
                }
            }
        }
        return visibility;
    }

    private Set<SectionFace> floodFill(int start, BitSet visited) {
        Set<SectionFace> faces = EnumSet.noneOf(SectionFace.class);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited.set(start);

        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int x = x(current);
            int y = y(current);
            int z = z(current);
            addBoundaryFaces(x, y, z, faces);

            for (SectionFace face : SectionFace.values()) {
                int nx = x + face.dx;
                int ny = y + face.dy;
                int nz = z + face.dz;
                if (!isInside(nx, ny, nz)) {
                    continue;
                }

                int neighbor = index(nx, ny, nz);
                if (!visited.get(neighbor)) {
                    visited.set(neighbor);
                    queue.add(neighbor);
                }
            }
        }
        return faces;
    }

    private void addBoundaryFaces(int x, int y, int z, Set<SectionFace> faces) {
        if (x == 0) {
            faces.add(SectionFace.WEST);
        } else if (x == SECTION_SIZE - 1) {
            faces.add(SectionFace.EAST);
        }

        if (y == 0) {
            faces.add(SectionFace.DOWN);
        } else if (y == SECTION_SIZE - 1) {
            faces.add(SectionFace.UP);
        }

        if (z == 0) {
            faces.add(SectionFace.NORTH);
        } else if (z == SECTION_SIZE - 1) {
            faces.add(SectionFace.SOUTH);
        }
    }

    private boolean isBoundary(int x, int y, int z) {
        return x == 0 || x == SECTION_SIZE - 1
            || y == 0 || y == SECTION_SIZE - 1
            || z == 0 || z == SECTION_SIZE - 1;
    }

    private boolean isInside(int x, int y, int z) {
        return x >= 0 && x < SECTION_SIZE
            && y >= 0 && y < SECTION_SIZE
            && z >= 0 && z < SECTION_SIZE;
    }

    private static int index(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    private static int x(int index) {
        return index & 15;
    }

    private static int z(int index) {
        return (index >> 4) & 15;
    }

    private static int y(int index) {
        return (index >> 8) & 15;
    }
}
