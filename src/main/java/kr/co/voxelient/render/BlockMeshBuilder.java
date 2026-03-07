package kr.co.voxelient.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Vector3;
import kr.co.voxelite.util.PerformanceLogger;
import kr.co.voxelite.world.BlockManager;
import kr.co.voxelite.world.Chunk;
import kr.co.voxelite.world.ChunkCoord;
import kr.co.voxelite.world.ChunkManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds render meshes for chunks.
 */
public class BlockMeshBuilder {
    private final Texture blockAtlas;
    private final int atlasGridSize = 16;
    private final float tileSize = 1.0f / atlasGridSize;
    private final float blockSize = 0.5f;
    private final BlockManager.IBlockTextureProvider textureProvider;

    public BlockMeshBuilder(String atlasPath, BlockManager.IBlockTextureProvider textureProvider) {
        blockAtlas = atlasPath != null ? new Texture(Gdx.files.internal(atlasPath)) : null;
        this.textureProvider = textureProvider;
    }

    public ChunkMesh buildChunkMesh(Chunk chunk, ChunkManager chunkManager) {
        long t0 = PerformanceLogger.now();
        Collection<Chunk.BlockData> blocks = chunk.getBlocks();
        if (blocks.isEmpty()) {
            return null;
        }

        int blockCount = blocks.size();
        List<BlockData> blockDataList = new ArrayList<>(blockCount);
        Map<Vector3, boolean[]> visibleFacesMap = new HashMap<>(Math.max(16, blockCount * 2));
        int skippedBlocks = 0;

        for (Chunk.BlockData block : blocks) {
            Vector3 pos = block.getWorldPos(chunk.getCoord());

            boolean[] visibleFaces = new boolean[6];
            visibleFaces[0] = !chunkManager.hasBlockAt(pos.x, pos.y, pos.z + 1);
            visibleFaces[1] = !chunkManager.hasBlockAt(pos.x, pos.y, pos.z - 1);
            visibleFaces[2] = !chunkManager.hasBlockAt(pos.x - 1, pos.y, pos.z);
            visibleFaces[3] = !chunkManager.hasBlockAt(pos.x + 1, pos.y, pos.z);
            visibleFaces[4] = !chunkManager.hasBlockAt(pos.x, pos.y + 1, pos.z);
            visibleFaces[5] = !chunkManager.hasBlockAt(pos.x, pos.y - 1, pos.z);

            if (!visibleFaces[0] && !visibleFaces[1] && !visibleFaces[2]
                && !visibleFaces[3] && !visibleFaces[4] && !visibleFaces[5]) {
                skippedBlocks++;
                continue;
            }

            blockDataList.add(new BlockData(pos, block.blockType, visibleFaces));
            visibleFacesMap.put(pos, visibleFaces);
        }

        Model chunkModel = createChunkMesh(blockDataList, visibleFacesMap);
        if (chunkModel == null) {
            return null;
        }

        ChunkMesh mesh = new ChunkMesh();
        mesh.setModel(chunkModel);

        if (PerformanceLogger.ENABLED) {
            long ms = PerformanceLogger.now() - t0;
            ChunkCoord coord = chunk.getCoord();
            System.out.printf("[PERF][BlockMeshBuilder] buildChunkMesh chunk(%d,%d): %d ms, blocks=%d, skipped=%d%n",
                coord.x, coord.z, ms, blockDataList.size(), skippedBlocks);
        }
        return mesh;
    }

    private Model createChunkMesh(List<BlockData> blocks, Map<Vector3, boolean[]> visibleFacesMap) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        long t0 = PerformanceLogger.now();
        List<GreedyMeshBuilder.MergedQuad> mergedQuads = GreedyMeshBuilder.buildGreedyMesh(blocks, visibleFacesMap);
        long t1 = PerformanceLogger.now();
        if (mergedQuads.isEmpty()) {
            return null;
        }

        ModelBuilder builder = new ModelBuilder();
        builder.begin();

        Material material = blockAtlas != null
            ? new Material(TextureAttribute.createDiffuse(blockAtlas))
            : new Material();

        long attributes = VertexAttributes.Usage.Position
            | VertexAttributes.Usage.Normal
            | VertexAttributes.Usage.TextureCoordinates;

        MeshPartBuilder meshBuilder = builder.part("chunk", GL20.GL_TRIANGLES, attributes, material);
        Vector3 normal = new Vector3();
        float s = blockSize;

        for (GreedyMeshBuilder.MergedQuad quad : mergedQuads) {
            int textureIndex = getTextureForFace(quad.blockType, quad.direction);
            int tileX = textureIndex % atlasGridSize;
            int tileY = textureIndex / atlasGridSize;
            float u = tileX * tileSize;
            float v = tileY * tileSize;
            createMergedFaceWithRepeatingUV(meshBuilder, quad.origin, quad.width, quad.height, quad.direction, s, normal, u, v, tileSize);
        }

        Model model = builder.end();
        long t2 = PerformanceLogger.now();
        if (PerformanceLogger.ENABLED && (t2 - t0) > 10) {
            System.out.printf("[PERF][BlockMeshBuilder] createChunkMesh: greedy=%dms mesh=%dms quads=%d blocks=%d%n",
                t1 - t0, t2 - t1, mergedQuads.size(), blocks.size());
        }
        return model;
    }

    private int getTextureForFace(int blockType, int faceIndex) {
        if (textureProvider != null) {
            return textureProvider.getTexture(blockType, faceIndex);
        }
        return blockType;
    }

    private void createMergedFaceWithRepeatingUV(
        MeshPartBuilder meshBuilder,
        Vector3 origin,
        int width,
        int height,
        int direction,
        float s,
        Vector3 normal,
        float u,
        float v,
        float tile
    ) {
        float blockWidth = 2 * s;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                float offsetX = 0f;
                float offsetY = 0f;
                float offsetZ = 0f;

                switch (direction) {
                    case 0:
                    case 1:
                        offsetX = i * blockWidth;
                        offsetY = j * blockWidth;
                        break;
                    case 2:
                    case 3:
                        offsetZ = i * blockWidth;
                        offsetY = j * blockWidth;
                        break;
                    case 4:
                    case 5:
                        offsetX = i * blockWidth;
                        offsetZ = j * blockWidth;
                        break;
                    default:
                        break;
                }

                createSingleBlockQuad(meshBuilder, origin, offsetX, offsetY, offsetZ, direction, s, normal, u, v, u + tile, v + tile);
            }
        }
    }

    private void createSingleBlockQuad(
        MeshPartBuilder meshBuilder,
        Vector3 origin,
        float offsetX,
        float offsetY,
        float offsetZ,
        int direction,
        float s,
        Vector3 normal,
        float u,
        float v,
        float u2,
        float v2
    ) {
        float x = origin.x;
        float y = origin.y;
        float z = origin.z;
        meshBuilder.setUVRange(u, v, u2, v2);

        switch (direction) {
            case 0:
                normal.set(0, 0, 1);
                meshBuilder.rect(
                    new Vector3(x - s + offsetX, y - s + offsetY, z + s),
                    new Vector3(x + s + offsetX, y - s + offsetY, z + s),
                    new Vector3(x + s + offsetX, y + s + offsetY, z + s),
                    new Vector3(x - s + offsetX, y + s + offsetY, z + s),
                    normal
                );
                break;
            case 1:
                normal.set(0, 0, -1);
                meshBuilder.rect(
                    new Vector3(x + s + offsetX, y - s + offsetY, z - s),
                    new Vector3(x - s + offsetX, y - s + offsetY, z - s),
                    new Vector3(x - s + offsetX, y + s + offsetY, z - s),
                    new Vector3(x + s + offsetX, y + s + offsetY, z - s),
                    normal
                );
                break;
            case 2:
                normal.set(-1, 0, 0);
                meshBuilder.rect(
                    new Vector3(x - s, y - s + offsetY, z - s + offsetZ),
                    new Vector3(x - s, y - s + offsetY, z + s + offsetZ),
                    new Vector3(x - s, y + s + offsetY, z + s + offsetZ),
                    new Vector3(x - s, y + s + offsetY, z - s + offsetZ),
                    normal
                );
                break;
            case 3:
                normal.set(1, 0, 0);
                meshBuilder.rect(
                    new Vector3(x + s, y - s + offsetY, z + s + offsetZ),
                    new Vector3(x + s, y - s + offsetY, z - s + offsetZ),
                    new Vector3(x + s, y + s + offsetY, z - s + offsetZ),
                    new Vector3(x + s, y + s + offsetY, z + s + offsetZ),
                    normal
                );
                break;
            case 4:
                normal.set(0, 1, 0);
                meshBuilder.rect(
                    new Vector3(x - s + offsetX, y + s, z + s + offsetZ),
                    new Vector3(x + s + offsetX, y + s, z + s + offsetZ),
                    new Vector3(x + s + offsetX, y + s, z - s + offsetZ),
                    new Vector3(x - s + offsetX, y + s, z - s + offsetZ),
                    normal
                );
                break;
            case 5:
                normal.set(0, -1, 0);
                meshBuilder.rect(
                    new Vector3(x - s + offsetX, y - s, z - s + offsetZ),
                    new Vector3(x + s + offsetX, y - s, z - s + offsetZ),
                    new Vector3(x + s + offsetX, y - s, z + s + offsetZ),
                    new Vector3(x - s + offsetX, y - s, z + s + offsetZ),
                    normal
                );
                break;
            default:
                break;
        }
    }

    public void dispose() {
        if (blockAtlas != null) {
            blockAtlas.dispose();
        }
    }

    public static class BlockData {
        public final Vector3 position;
        public final int blockType;
        public final boolean[] visibleFaces;

        public BlockData(Vector3 position, int blockType, boolean[] visibleFaces) {
            this.position = position;
            this.blockType = blockType;
            this.visibleFaces = visibleFaces;
        }
    }
}
