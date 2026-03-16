package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import kr.co.voxelite.world.BlockManager;
import kr.co.voxelite.world.Chunk;
import kr.co.voxelite.world.ChunkCoord;
import kr.co.voxelite.world.ChunkManager;
import kr.co.voxelite.world.RenderSectionKey;
import kr.co.voxelite.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages client-side chunk mesh cache.
 */
public class ChunkMeshManager {
    private final World world;
    private final BlockMeshBuilder meshBuilder;
    private final Map<RenderSectionKey, ChunkMesh> meshes = new HashMap<>();
    private final Map<RenderSectionKey, Integer> buildVersions = new HashMap<>();
    private final Queue<CompileBatchResult> completedCompiles = new ConcurrentLinkedQueue<>();
    private final ExecutorService compileExecutor = Executors.newFixedThreadPool(2);

    public ChunkMeshManager(World world, String textureAtlasPath, BlockManager.IBlockTextureProvider textureProvider) {
        this.world = world;
        meshBuilder = new BlockMeshBuilder(textureAtlasPath, textureProvider);
    }

    public void processDirtyChunks(int maxPerFrame) {
        ChunkManager chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            return;
        }

        applyCompletedCompiles(chunkManager);
        sweepStaleMeshes();

        int selectedChunks = 0;
        Map<ChunkCoord, Set<Integer>> dirtySectionsByChunk = new LinkedHashMap<>();
        while (selectedChunks < maxPerFrame) {
            RenderSectionKey key = chunkManager.pollDirtySection();
            if (key == null) {
                break;
            }

            ChunkCoord coord = key.chunkCoord();
            Set<Integer> sections = dirtySectionsByChunk.get(coord);
            if (sections == null) {
                sections = new HashSet<>();
                dirtySectionsByChunk.put(coord, sections);
                selectedChunks++;
            }

            sections.add(key.sectionY());
            sections.addAll(chunkManager.drainDirtySections(coord));
        }

        for (Map.Entry<ChunkCoord, Set<Integer>> entry : dirtySectionsByChunk.entrySet()) {
            ChunkCoord coord = entry.getKey();
            Chunk chunk = chunkManager.getChunk(coord);
            if (chunk == null || !chunk.isGenerated()) {
                disposeChunkMeshes(coord);
                continue;
            }

            enqueueChunkSectionsCompile(coord, entry.getValue(), chunk, chunkManager);
        }
    }

    public List<ModelInstance> getVisibleInstances(Camera camera) {
        ChunkManager chunkManager = world.getChunkManager();
        List<ModelInstance> visibleInstances = new ArrayList<>(meshes.size());
        if (chunkManager == null) {
            return visibleInstances;
        }

        sweepStaleMeshes();

        for (Map.Entry<RenderSectionKey, ChunkMesh> entry : meshes.entrySet()) {
            RenderSectionKey key = entry.getKey();
            Chunk chunk = chunkManager.getChunk(key.chunkCoord());
            if (chunk == null || !chunk.isGenerated() || !chunkManager.isChunkVisible(key.chunkCoord())) {
                continue;
            }

            ChunkMesh mesh = entry.getValue();
            if (mesh == null || !mesh.hasInstance()) {
                continue;
            }

            if (camera == null || camera.frustum.boundsInFrustum(mesh.getBounds())) {
                visibleInstances.add(mesh.getInstance());
            }
        }
        return visibleInstances;
    }

    public void dispose() {
        compileExecutor.shutdownNow();
        try {
            if (!compileExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                compileExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            compileExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (ChunkMesh mesh : meshes.values()) {
            mesh.dispose();
        }
        meshes.clear();
        buildVersions.clear();
        completedCompiles.clear();
        meshBuilder.dispose();
    }

    private void sweepStaleMeshes() {
        ChunkManager chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            dispose();
            return;
        }

        Iterator<Map.Entry<RenderSectionKey, ChunkMesh>> iterator = meshes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RenderSectionKey, ChunkMesh> entry = iterator.next();
            Chunk chunk = chunkManager.getChunk(entry.getKey().chunkCoord());
            if (chunk == null || !chunk.isGenerated()) {
                entry.getValue().dispose();
                buildVersions.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    private void disposeChunkMeshes(ChunkCoord coord) {
        Iterator<Map.Entry<RenderSectionKey, ChunkMesh>> iterator = meshes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RenderSectionKey, ChunkMesh> entry = iterator.next();
            if (!entry.getKey().chunkCoord().equals(coord)) {
                continue;
            }

            entry.getValue().dispose();
            buildVersions.remove(entry.getKey());
            iterator.remove();
        }
    }

    private void enqueueChunkSectionsCompile(ChunkCoord coord, Set<Integer> sections, Chunk chunk, ChunkManager chunkManager) {
        if (sections == null || sections.isEmpty()) {
            return;
        }

        Map<RenderSectionKey, BlockMeshBuilder.SectionBuildInput> sectionInputs =
            meshBuilder.prepareSectionBuildInputs(chunk, chunkManager, sections);
        if (sectionInputs.isEmpty()) {
            return;
        }

        Map<RenderSectionKey, Integer> versions = new HashMap<>();
        for (RenderSectionKey key : sectionInputs.keySet()) {
            int version = buildVersions.getOrDefault(key, 0) + 1;
            buildVersions.put(key, version);
            versions.put(key, version);
        }

        compileExecutor.submit(() -> {
            Map<RenderSectionKey, BlockMeshBuilder.CompiledSectionMesh> compiledSections =
                meshBuilder.compileSectionMeshes(sectionInputs);
            completedCompiles.offer(new CompileBatchResult(Map.copyOf(versions), Map.copyOf(compiledSections)));
        });
    }

    private void disposeSectionMesh(RenderSectionKey key) {
        ChunkMesh mesh = meshes.remove(key);
        if (mesh != null) {
            mesh.dispose();
        }
    }

    private void applyCompletedCompiles(ChunkManager chunkManager) {
        CompileBatchResult result;
        while ((result = completedCompiles.poll()) != null) {
            for (Map.Entry<RenderSectionKey, Integer> versionEntry : result.versions().entrySet()) {
                RenderSectionKey key = versionEntry.getKey();
                Integer expectedVersion = buildVersions.get(key);
                if (expectedVersion == null || !expectedVersion.equals(versionEntry.getValue())) {
                    continue;
                }

                Chunk chunk = chunkManager.getChunk(key.chunkCoord());
                if (chunk == null || !chunk.isGenerated()) {
                    disposeSectionMesh(key);
                    buildVersions.remove(key);
                    continue;
                }

                disposeSectionMesh(key);
                ChunkMesh mesh = meshBuilder.buildCompiledChunkMesh(result.compiledSections().get(key));
                if (mesh != null) {
                    meshes.put(key, mesh);
                }
            }
        }
    }

    private record CompileBatchResult(
        Map<RenderSectionKey, Integer> versions,
        Map<RenderSectionKey, BlockMeshBuilder.CompiledSectionMesh> compiledSections
    ) {
    }
}
