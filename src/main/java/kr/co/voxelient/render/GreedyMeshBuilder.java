package kr.co.voxelient.render;

import com.badlogic.gdx.math.Vector3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Greedy Meshing algorithm implementation.
 */
public class GreedyMeshBuilder {
    public static class MergedQuad {
        public final Vector3 origin;
        public final int width;
        public final int height;
        public final int blockType;
        public final int direction;

        public MergedQuad(Vector3 origin, int width, int height, int blockType, int direction) {
            this.origin = origin;
            this.width = width;
            this.height = height;
            this.blockType = blockType;
            this.direction = direction;
        }
    }

    private static class VoxelGrid {
        private final BlockMeshBuilder.BlockData[][][] grid;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;

        public VoxelGrid(List<BlockMeshBuilder.BlockData> blocks, Map<Vector3, boolean[]> visibleFacesMap) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;

            for (BlockMeshBuilder.BlockData block : blocks) {
                Vector3 pos = block.position;
                int x = (int) pos.x;
                int y = (int) pos.y;
                int z = (int) pos.z;

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }

            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            grid = new BlockMeshBuilder.BlockData[maxX - minX + 1][maxY - minY + 1][maxZ - minZ + 1];

            for (BlockMeshBuilder.BlockData block : blocks) {
                Vector3 pos = block.position;
                int x = (int) pos.x;
                int y = (int) pos.y;
                int z = (int) pos.z;

                boolean[] visibleFaces = visibleFacesMap.get(pos);
                if (visibleFaces != null) {
                    grid[x - minX][y - minY][z - minZ] =
                        new BlockMeshBuilder.BlockData(new Vector3(pos), block.blockType, visibleFaces);
                }
            }
        }

        public BlockMeshBuilder.BlockData get(int x, int y, int z) {
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
                return null;
            }
            return grid[x - minX][y - minY][z - minZ];
        }

        public boolean hasFace(int x, int y, int z, int direction) {
            BlockMeshBuilder.BlockData info = get(x, y, z);
            return info != null && info.visibleFaces[direction];
        }

        public int getBlockType(int x, int y, int z) {
            BlockMeshBuilder.BlockData info = get(x, y, z);
            return info != null ? info.blockType : -1;
        }
    }

    public static List<MergedQuad> buildGreedyMesh(
        List<BlockMeshBuilder.BlockData> blocks,
        Map<Vector3, boolean[]> visibleFacesMap
    ) {
        if (blocks == null || blocks.isEmpty()) {
            return new ArrayList<>();
        }

        VoxelGrid grid = new VoxelGrid(blocks, visibleFacesMap);
        List<MergedQuad> quads = new ArrayList<>();
        quads.addAll(buildFacesForDirection(grid, 0));
        quads.addAll(buildFacesForDirection(grid, 1));
        quads.addAll(buildFacesForDirection(grid, 2));
        quads.addAll(buildFacesForDirection(grid, 3));
        quads.addAll(buildFacesForDirection(grid, 4));
        quads.addAll(buildFacesForDirection(grid, 5));
        return quads;
    }

    private static List<MergedQuad> buildFacesForDirection(VoxelGrid grid, int direction) {
        List<MergedQuad> quads = new ArrayList<>();
        switch (direction) {
            case 0:
            case 1:
                buildFacesXY(grid, direction, quads);
                break;
            case 2:
            case 3:
                buildFacesZY(grid, direction, quads);
                break;
            case 4:
            case 5:
                buildFacesXZ(grid, direction, quads);
                break;
            default:
                break;
        }
        return quads;
    }

    private static void buildFacesXY(VoxelGrid grid, int direction, List<MergedQuad> quads) {
        boolean[][][] visited = new boolean[grid.maxX - grid.minX + 1][grid.maxY - grid.minY + 1][grid.maxZ - grid.minZ + 1];

        for (int z = grid.minZ; z <= grid.maxZ; z++) {
            for (int y = grid.minY; y <= grid.maxY; y++) {
                for (int x = grid.minX; x <= grid.maxX; x++) {
                    if (visited[x - grid.minX][y - grid.minY][z - grid.minZ] || !grid.hasFace(x, y, z, direction)) {
                        continue;
                    }

                    int blockType = grid.getBlockType(x, y, z);
                    int width = 1;
                    while (x + width <= grid.maxX
                        && !visited[x + width - grid.minX][y - grid.minY][z - grid.minZ]
                        && grid.hasFace(x + width, y, z, direction)
                        && grid.getBlockType(x + width, y, z) == blockType) {
                        width++;
                    }

                    int height = 1;
                    boolean canExpandY = true;
                    while (canExpandY && y + height <= grid.maxY) {
                        for (int dx = 0; dx < width; dx++) {
                            if (visited[x + dx - grid.minX][y + height - grid.minY][z - grid.minZ]
                                || !grid.hasFace(x + dx, y + height, z, direction)
                                || grid.getBlockType(x + dx, y + height, z) != blockType) {
                                canExpandY = false;
                                break;
                            }
                        }
                        if (canExpandY) {
                            height++;
                        }
                    }

                    quads.add(new MergedQuad(new Vector3(x, y, z), width, height, blockType, direction));
                    for (int dy = 0; dy < height; dy++) {
                        for (int dx = 0; dx < width; dx++) {
                            visited[x + dx - grid.minX][y + dy - grid.minY][z - grid.minZ] = true;
                        }
                    }
                }
            }
        }
    }

    private static void buildFacesZY(VoxelGrid grid, int direction, List<MergedQuad> quads) {
        boolean[][][] visited = new boolean[grid.maxX - grid.minX + 1][grid.maxY - grid.minY + 1][grid.maxZ - grid.minZ + 1];

        for (int x = grid.minX; x <= grid.maxX; x++) {
            for (int y = grid.minY; y <= grid.maxY; y++) {
                for (int z = grid.minZ; z <= grid.maxZ; z++) {
                    if (visited[x - grid.minX][y - grid.minY][z - grid.minZ] || !grid.hasFace(x, y, z, direction)) {
                        continue;
                    }

                    int blockType = grid.getBlockType(x, y, z);
                    int width = 1;
                    while (z + width <= grid.maxZ
                        && !visited[x - grid.minX][y - grid.minY][z + width - grid.minZ]
                        && grid.hasFace(x, y, z + width, direction)
                        && grid.getBlockType(x, y, z + width) == blockType) {
                        width++;
                    }

                    int height = 1;
                    boolean canExpandY = true;
                    while (canExpandY && y + height <= grid.maxY) {
                        for (int dz = 0; dz < width; dz++) {
                            if (visited[x - grid.minX][y + height - grid.minY][z + dz - grid.minZ]
                                || !grid.hasFace(x, y + height, z + dz, direction)
                                || grid.getBlockType(x, y + height, z + dz) != blockType) {
                                canExpandY = false;
                                break;
                            }
                        }
                        if (canExpandY) {
                            height++;
                        }
                    }

                    quads.add(new MergedQuad(new Vector3(x, y, z), width, height, blockType, direction));
                    for (int dy = 0; dy < height; dy++) {
                        for (int dz = 0; dz < width; dz++) {
                            visited[x - grid.minX][y + dy - grid.minY][z + dz - grid.minZ] = true;
                        }
                    }
                }
            }
        }
    }

    private static void buildFacesXZ(VoxelGrid grid, int direction, List<MergedQuad> quads) {
        boolean[][][] visited = new boolean[grid.maxX - grid.minX + 1][grid.maxY - grid.minY + 1][grid.maxZ - grid.minZ + 1];

        for (int y = grid.minY; y <= grid.maxY; y++) {
            for (int z = grid.minZ; z <= grid.maxZ; z++) {
                for (int x = grid.minX; x <= grid.maxX; x++) {
                    if (visited[x - grid.minX][y - grid.minY][z - grid.minZ] || !grid.hasFace(x, y, z, direction)) {
                        continue;
                    }

                    int blockType = grid.getBlockType(x, y, z);
                    int width = 1;
                    while (x + width <= grid.maxX
                        && !visited[x + width - grid.minX][y - grid.minY][z - grid.minZ]
                        && grid.hasFace(x + width, y, z, direction)
                        && grid.getBlockType(x + width, y, z) == blockType) {
                        width++;
                    }

                    int depth = 1;
                    boolean canExpandZ = true;
                    while (canExpandZ && z + depth <= grid.maxZ) {
                        for (int dx = 0; dx < width; dx++) {
                            if (visited[x + dx - grid.minX][y - grid.minY][z + depth - grid.minZ]
                                || !grid.hasFace(x + dx, y, z + depth, direction)
                                || grid.getBlockType(x + dx, y, z + depth) != blockType) {
                                canExpandZ = false;
                                break;
                            }
                        }
                        if (canExpandZ) {
                            depth++;
                        }
                    }

                    quads.add(new MergedQuad(new Vector3(x, y, z), width, depth, blockType, direction));
                    for (int dz = 0; dz < depth; dz++) {
                        for (int dx = 0; dx < width; dx++) {
                            visited[x + dx - grid.minX][y - grid.minY][z + dz - grid.minZ] = true;
                        }
                    }
                }
            }
        }
    }
}
