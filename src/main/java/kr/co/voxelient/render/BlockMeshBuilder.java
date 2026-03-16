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
import com.badlogic.gdx.math.collision.BoundingBox;
import kr.co.voxelite.util.PerformanceLogger;
import kr.co.voxelite.world.BlockManager;
import kr.co.voxelite.world.Chunk;
import kr.co.voxelite.world.ChunkCoord;
import kr.co.voxelite.world.ChunkManager;
import kr.co.voxelite.world.RenderSectionKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public Map<RenderSectionKey, ChunkMesh> buildChunkMeshes(Chunk chunk, ChunkManager chunkManager) {
        Set<Integer> allSections = new HashSet<>();
        for (int sectionY = 0; sectionY < Chunk.getRenderSectionCount(); sectionY++) {
            allSections.add(sectionY);
        }
        return buildChunkMeshes(chunk, chunkManager, allSections);
    }

    public Map<RenderSectionKey, ChunkMesh> buildChunkMeshes(Chunk chunk, ChunkManager chunkManager, Collection<Integer> targetSections) {
        Map<RenderSectionKey, SectionBuildInput> sectionInputs = prepareSectionBuildInputs(chunk, chunkManager, targetSections);
        Map<RenderSectionKey, CompiledSectionMesh> compiledSections = compileSectionMeshes(sectionInputs);
        return buildCompiledChunkMeshes(compiledSections);
    }

    public Map<RenderSectionKey, SectionBuildInput> prepareSectionBuildInputs(
        Chunk chunk,
        ChunkManager chunkManager,
        Collection<Integer> targetSections
    ) {
        long t0 = PerformanceLogger.now();
        ChunkCoord chunkCoord = chunk.getCoord();
        Collection<Chunk.BlockData> blocks = chunk.getBlocks();
        if (targetSections == null || targetSections.isEmpty()) {
            return Map.of();
        }

        Set<Integer> requestedSections = new HashSet<>();
        for (Integer sectionY : targetSections) {
            if (sectionY != null && Chunk.isValidRenderSectionIndex(sectionY)) {
                requestedSections.add(sectionY);
            }
        }
        if (requestedSections.isEmpty()) {
            return Map.of();
        }

        Map<Integer, SectionBuildAccumulator> sectionData = new HashMap<>();
        for (Integer sectionY : requestedSections) {
            sectionData.put(sectionY, new SectionBuildAccumulator(chunk.getRenderSectionBounds(sectionY)));
        }

        int skippedBlocks = 0;

        for (Chunk.BlockData block : blocks) {
            Vector3 pos = block.getWorldPos(chunkCoord);
            int sectionY = Chunk.getRenderSectionIndex(block.pos.y());
            if (!requestedSections.contains(sectionY)) {
                continue;
            }

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

            Vector3 copiedPos = new Vector3(pos);
            boolean[] copiedVisibleFaces = visibleFaces.clone();
            SectionBuildAccumulator accumulator = sectionData.get(sectionY);
            accumulator.blocks.add(new BlockData(copiedPos, block.blockType, copiedVisibleFaces));
            accumulator.visibleFacesMap.put(copiedPos, copiedVisibleFaces);
        }

        Map<RenderSectionKey, SectionBuildInput> sectionInputs = new HashMap<>();
        for (Map.Entry<Integer, SectionBuildAccumulator> entry : sectionData.entrySet()) {
            int sectionY = entry.getKey();
            SectionBuildAccumulator accumulator = entry.getValue();
            RenderSectionKey key = new RenderSectionKey(chunkCoord, sectionY);
            sectionInputs.put(key, new SectionBuildInput(
                key,
                List.copyOf(accumulator.blocks),
                Map.copyOf(accumulator.visibleFacesMap),
                new BoundingBox(accumulator.bounds.min.cpy(), accumulator.bounds.max.cpy())
            ));
        }

        if (PerformanceLogger.ENABLED) {
            long ms = PerformanceLogger.now() - t0;
            System.out.printf("[PERF][BlockMeshBuilder] prepareSectionBuildInputs chunk(%d,%d): %d ms, sections=%d, skipped=%d%n",
                chunkCoord.x, chunkCoord.z, ms, sectionInputs.size(), skippedBlocks);
        }
        return sectionInputs;
    }

    public Map<RenderSectionKey, CompiledSectionMesh> compileSectionMeshes(Map<RenderSectionKey, SectionBuildInput> sectionInputs) {
        if (sectionInputs == null || sectionInputs.isEmpty()) {
            return Map.of();
        }

        Map<RenderSectionKey, CompiledSectionMesh> compiledSections = new HashMap<>();
        for (Map.Entry<RenderSectionKey, SectionBuildInput> entry : sectionInputs.entrySet()) {
            SectionBuildInput input = entry.getValue();
            List<GreedyMeshBuilder.MergedQuad> mergedQuads = GreedyMeshBuilder.buildGreedyMesh(input.blocks(), input.visibleFacesMap());
            compiledSections.put(entry.getKey(), new CompiledSectionMesh(
                entry.getKey(),
                List.copyOf(mergedQuads),
                new BoundingBox(input.bounds().min.cpy(), input.bounds().max.cpy())
            ));
        }
        return compiledSections;
    }

    public Map<RenderSectionKey, ChunkMesh> buildCompiledChunkMeshes(Map<RenderSectionKey, CompiledSectionMesh> compiledSections) {
        if (compiledSections == null || compiledSections.isEmpty()) {
            return Map.of();
        }

        Map<RenderSectionKey, ChunkMesh> sectionMeshes = new HashMap<>();
        for (Map.Entry<RenderSectionKey, CompiledSectionMesh> entry : compiledSections.entrySet()) {
            ChunkMesh mesh = buildCompiledChunkMesh(entry.getValue());
            if (mesh != null) {
                sectionMeshes.put(entry.getKey(), mesh);
            }
        }
        return sectionMeshes;
    }

    public ChunkMesh buildCompiledChunkMesh(CompiledSectionMesh compiledSection) {
        if (compiledSection == null || compiledSection.mergedQuads().isEmpty()) {
            return null;
        }

        long t0 = PerformanceLogger.now();
        List<GreedyMeshBuilder.MergedQuad> mergedQuads = compiledSection.mergedQuads();
        ModelBuilder builder = new ModelBuilder();
        builder.begin();

        Material opaqueMaterial = blockAtlas != null
            ? new Material(TextureAttribute.createDiffuse(blockAtlas))
            : new Material();

        long attributes = VertexAttributes.Usage.Position
            | VertexAttributes.Usage.Normal
            | VertexAttributes.Usage.TextureCoordinates;

        MeshPartBuilder meshPartBuilder = null;
        Vector3 normal = new Vector3();
        float s = blockSize;

        for (GreedyMeshBuilder.MergedQuad quad : mergedQuads) {
            if (meshPartBuilder == null) {
                meshPartBuilder = builder.part("chunk", GL20.GL_TRIANGLES, attributes, opaqueMaterial);
            }

            int textureIndex = getTextureForFace(quad.blockType, quad.direction);
            int tileX = textureIndex % atlasGridSize;
            int tileY = textureIndex / atlasGridSize;
            float u = tileX * tileSize;
            float v = tileY * tileSize;

            createMergedFaceWithRepeatingUV(
                meshPartBuilder,
                quad.origin,
                quad.width,
                quad.height,
                quad.direction,
                s,
                normal,
                u,
                v,
                tileSize
            );
        }

        Model model = builder.end();
        long t1 = PerformanceLogger.now();
        if (PerformanceLogger.ENABLED && (t1 - t0) > 10) {
            System.out.printf("[PERF][BlockMeshBuilder] buildCompiledChunkMesh: mesh=%dms quads=%d%n",
                t1 - t0, mergedQuads.size());
        }

        ChunkMesh mesh = new ChunkMesh();
        mesh.setModel(model);
        mesh.setBounds(compiledSection.bounds());
        return mesh;
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

    public record SectionBuildInput(
        RenderSectionKey key,
        List<BlockData> blocks,
        Map<Vector3, boolean[]> visibleFacesMap,
        BoundingBox bounds
    ) {
    }

    public record CompiledSectionMesh(
        RenderSectionKey key,
        List<GreedyMeshBuilder.MergedQuad> mergedQuads,
        BoundingBox bounds
    ) {
    }

    private static class SectionBuildAccumulator {
        private final List<BlockData> blocks = new ArrayList<>();
        private final Map<Vector3, boolean[]> visibleFacesMap = new HashMap<>();
        private final BoundingBox bounds;

        private SectionBuildAccumulator(BoundingBox bounds) {
            this.bounds = bounds;
        }
    }
}
