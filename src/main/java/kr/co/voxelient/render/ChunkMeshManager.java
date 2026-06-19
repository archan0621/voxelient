package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.collision.BoundingBox;
import kr.co.voxelite.util.PerformanceLogger;
import kr.co.voxelite.world.BlockManager;
import kr.co.voxelite.world.BlockRenderLayer;
import kr.co.voxelite.world.Chunk;
import kr.co.voxelite.world.ChunkCoord;
import kr.co.voxelite.world.ChunkManager;
import kr.co.voxelite.world.RenderSectionKey;
import kr.co.voxelite.world.World;

import java.util.ArrayDeque;
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
    private static final int MAX_IN_FLIGHT_COMPILES = 2;
    private static final int MAX_COMPLETED_BATCH_DRAINS_PER_FRAME = 1;
    private static final int MAX_PENDING_MESH_APPLICATIONS = 64;
    private static final long DEFAULT_MESH_APPLY_BUDGET_MS = 4L;
    private static final SectionVisibility DEFAULT_VISIBILITY = SectionVisibility.allVisible();

    private final World world;
    private final BlockMeshBuilder meshBuilder;
    private final Map<RenderSectionKey, ChunkMesh> meshes = new HashMap<>();
    private final Map<RenderSectionKey, SectionVisibility> sectionVisibility = new HashMap<>();
    private final Map<RenderSectionKey, Integer> buildVersions = new HashMap<>();
    private final Queue<CompileBatchResult> completedCompiles = new ConcurrentLinkedQueue<>();
    private final Queue<CompiledSectionApplication> pendingMeshApplications = new ConcurrentLinkedQueue<>();
    private final ExecutorService compileExecutor = Executors.newFixedThreadPool(2);
    private int inFlightCompiles = 0;
    private int visibilityRevision = 0;
    private int cachedTraversalRevision = -1;
    private RenderSectionKey cachedTraversalStart = null;
    private Set<RenderSectionKey> cachedTraversableSections = null;
    private ChunkMeshStats stats = ChunkMeshStats.empty();

    public ChunkMeshManager(
        World world,
        String textureAtlasPath,
        BlockManager.IBlockTextureProvider textureProvider,
        BlockManager.IBlockRenderLayerProvider renderLayerProvider
    ) {
        this.world = world;
        meshBuilder = new BlockMeshBuilder(textureAtlasPath, textureProvider, renderLayerProvider);
    }

    public void processDirtyChunks(int maxPerFrame) {
        processDirtyChunks(maxPerFrame, Math.max(4, maxPerFrame * 2), DEFAULT_MESH_APPLY_BUDGET_MS);
    }

    public void processDirtyChunks(int maxCompileBatchesPerFrame, int maxMeshApplicationsPerFrame, long meshApplyBudgetMs) {
        ChunkManager chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            return;
        }

        MeshApplySummary applySummary = applyPendingMeshApplications(
            chunkManager,
            RenderFrameBudget.of(maxMeshApplicationsPerFrame, meshApplyBudgetMs)
        );
        int completedBatches = drainCompletedCompiles(MAX_COMPLETED_BATCH_DRAINS_PER_FRAME);
        sweepStaleMeshes();

        boolean compileThrottled = pendingMeshApplications.size() >= MAX_PENDING_MESH_APPLICATIONS
            || completedCompiles.size() > 0;
        int selectedChunks = 0;
        int queuedCompileBatches = 0;
        int availableCompileSlots = Math.min(
            Math.max(0, maxCompileBatchesPerFrame),
            MAX_IN_FLIGHT_COMPILES - inFlightCompiles
        );
        if (!compileThrottled && availableCompileSlots > 0) {
            Map<ChunkCoord, Set<Integer>> dirtySectionsByChunk = new LinkedHashMap<>();
            while (selectedChunks < availableCompileSlots) {
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

                if (enqueueChunkSectionsCompile(coord, entry.getValue(), chunk, chunkManager)) {
                    queuedCompileBatches++;
                }
            }
        }

        stats = new ChunkMeshStats(
            meshes.size(),
            sectionVisibility.size(),
            inFlightCompiles,
            completedBatches,
            pendingMeshApplications.size(),
            selectedChunks,
            queuedCompileBatches,
            applySummary.applied(),
            applySummary.discarded(),
            applySummary.elapsedMs(),
            compileThrottled
        );
    }

    public ChunkMeshStats getStats() {
        return stats;
    }

    public int getMeshSectionCount() {
        return meshes.size();
    }

    public int getPendingMeshApplicationCount() {
        return pendingMeshApplications.size();
    }

    public int getInFlightCompileCount() {
        return inFlightCompiles;
    }

    private int drainCompletedCompiles(int maxBatches) {
        int drained = 0;
        CompileBatchResult result;
        while (drained < maxBatches
            && pendingMeshApplications.size() < MAX_PENDING_MESH_APPLICATIONS
            && (result = completedCompiles.poll()) != null) {
            inFlightCompiles = Math.max(0, inFlightCompiles - 1);
            drained++;
            for (Map.Entry<RenderSectionKey, Integer> versionEntry : result.versions().entrySet()) {
                RenderSectionKey key = versionEntry.getKey();
                pendingMeshApplications.offer(new CompiledSectionApplication(
                    key,
                    versionEntry.getValue(),
                    result.compiledSections().get(key)
                ));
            }
        }
        return drained;
    }

    private MeshApplySummary applyPendingMeshApplications(ChunkManager chunkManager, RenderFrameBudget budget) {
        long startMs = PerformanceLogger.now();
        int applied = 0;
        int discarded = 0;

        while (pendingMeshApplications.peek() != null && budget.tryUse()) {
            CompiledSectionApplication application = pendingMeshApplications.poll();
            if (application == null) {
                break;
            }

            if (applyCompiledSection(chunkManager, application)) {
                applied++;
            } else {
                discarded++;
            }
        }

        return new MeshApplySummary(applied, discarded, PerformanceLogger.now() - startMs);
    }

    private boolean applyCompiledSection(ChunkManager chunkManager, CompiledSectionApplication application) {
        RenderSectionKey key = application.key();
        Integer expectedVersion = buildVersions.get(key);
        if (expectedVersion == null || !expectedVersion.equals(application.version())) {
            return false;
        }

        Chunk chunk = chunkManager.getChunk(key.chunkCoord());
        if (chunk == null || !chunk.isGenerated()) {
            disposeSectionMesh(key);
            sectionVisibility.remove(key);
            buildVersions.remove(key);
            invalidateTraversalCache();
            return false;
        }

        BlockMeshBuilder.CompiledSectionMesh compiledSection = application.compiledSection();
        if (compiledSection == null) {
            disposeSectionMesh(key);
            sectionVisibility.remove(key);
            invalidateTraversalCache();
            return true;
        }

        sectionVisibility.put(key, compiledSection.visibility());
        invalidateTraversalCache();
        disposeSectionMesh(key);
        ChunkMesh mesh = meshBuilder.buildCompiledChunkMesh(compiledSection);
        if (mesh != null) {
            meshes.put(key, mesh);
        }
        return true;
    }

    private void sweepStaleMeshes() {
        ChunkManager chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            dispose();
            return;
        }

        Iterator<Map.Entry<RenderSectionKey, ChunkMesh>> iterator = meshes.entrySet().iterator();
        boolean removedMesh = false;
        while (iterator.hasNext()) {
            Map.Entry<RenderSectionKey, ChunkMesh> entry = iterator.next();
            Chunk chunk = chunkManager.getChunk(entry.getKey().chunkCoord());
            if (chunk == null || !chunk.isGenerated()) {
                entry.getValue().dispose();
                sectionVisibility.remove(entry.getKey());
                buildVersions.remove(entry.getKey());
                iterator.remove();
                removedMesh = true;
            }
        }

        boolean removedVisibility = sectionVisibility.keySet().removeIf(key -> {
            Chunk chunk = chunkManager.getChunk(key.chunkCoord());
            return chunk == null || !chunk.isGenerated();
        });
        if (removedMesh || removedVisibility) {
            invalidateTraversalCache();
        }
    }

    public List<ModelInstance> getVisibleInstances(Camera camera) {
        List<ModelInstance> visibleInstances = new ArrayList<>();
        for (BlockRenderLayer renderLayer : BlockRenderLayer.values()) {
            visibleInstances.addAll(getVisibleInstances(camera, renderLayer));
        }
        return visibleInstances;
    }

    public List<ModelInstance> getVisibleInstances(Camera camera, BlockRenderLayer renderLayer) {
        ChunkManager chunkManager = world.getChunkManager();
        List<LayerInstance> visibleInstances = new ArrayList<>(meshes.size());
        if (chunkManager == null) {
            return List.of();
        }

        RenderSectionKey cameraSection = getCameraSection(camera);
        Set<RenderSectionKey> traversableSections = cameraSection != null
            ? getCachedTraversableSections(cameraSection, chunkManager)
            : null;

        for (Map.Entry<RenderSectionKey, ChunkMesh> entry : meshes.entrySet()) {
            RenderSectionKey key = entry.getKey();
            Chunk chunk = chunkManager.getChunk(key.chunkCoord());
            if (chunk == null || !chunk.isGenerated() || !chunkManager.isChunkVisible(key.chunkCoord())) {
                continue;
            }
            if (traversableSections != null
                && !traversableSections.contains(key)
                && !isNearCameraSection(key, cameraSection)) {
                continue;
            }

            ChunkMesh mesh = entry.getValue();
            if (mesh == null || !mesh.hasInstance(renderLayer)) {
                continue;
            }

            boolean nearCameraSection = isNearCameraSection(key, cameraSection);
            if (camera == null || nearCameraSection || camera.frustum.boundsInFrustum(mesh.getBounds())) {
                visibleInstances.add(new LayerInstance(mesh.getInstance(renderLayer), mesh.getBounds()));
            }
        }

        if (renderLayer == BlockRenderLayer.TRANSLUCENT && camera != null) {
            visibleInstances = TranslucentRenderOrder.backToFront(
                camera,
                visibleInstances,
                LayerInstance::bounds
            );
        }

        List<ModelInstance> instances = new ArrayList<>(visibleInstances.size());
        for (LayerInstance visibleInstance : visibleInstances) {
            instances.add(visibleInstance.instance());
        }
        return instances;
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
        sectionVisibility.clear();
        buildVersions.clear();
        completedCompiles.clear();
        pendingMeshApplications.clear();
        stats = ChunkMeshStats.empty();
        meshBuilder.dispose();
        inFlightCompiles = 0;
        invalidateTraversalCache();
    }

    private void disposeChunkMeshes(ChunkCoord coord) {
        Iterator<Map.Entry<RenderSectionKey, ChunkMesh>> iterator = meshes.entrySet().iterator();
        boolean removedMesh = false;
        while (iterator.hasNext()) {
            Map.Entry<RenderSectionKey, ChunkMesh> entry = iterator.next();
            if (!entry.getKey().chunkCoord().equals(coord)) {
                continue;
            }

            entry.getValue().dispose();
            sectionVisibility.remove(entry.getKey());
            buildVersions.remove(entry.getKey());
            iterator.remove();
            removedMesh = true;
        }

        boolean removedVisibility = sectionVisibility.keySet().removeIf(key -> key.chunkCoord().equals(coord));
        if (removedMesh || removedVisibility) {
            invalidateTraversalCache();
        }
    }

    private boolean enqueueChunkSectionsCompile(ChunkCoord coord, Set<Integer> sections, Chunk chunk, ChunkManager chunkManager) {
        if (sections == null || sections.isEmpty()) {
            return false;
        }

        Map<RenderSectionKey, BlockMeshBuilder.SectionBuildInput> sectionInputs =
            meshBuilder.prepareSectionBuildInputs(chunk, chunkManager, sections);
        if (sectionInputs.isEmpty()) {
            return false;
        }

        Map<RenderSectionKey, Integer> versions = new HashMap<>();
        for (RenderSectionKey key : sectionInputs.keySet()) {
            int version = buildVersions.getOrDefault(key, 0) + 1;
            buildVersions.put(key, version);
            versions.put(key, version);
        }

        inFlightCompiles++;
        try {
            compileExecutor.submit(() -> {
                Map<RenderSectionKey, BlockMeshBuilder.CompiledSectionMesh> compiledSections;
                try {
                    compiledSections = meshBuilder.compileSectionMeshes(sectionInputs);
                } catch (Exception e) {
                    e.printStackTrace();
                    compiledSections = Map.of();
                }
                completedCompiles.offer(new CompileBatchResult(Map.copyOf(versions), Map.copyOf(compiledSections)));
            });
        } catch (RuntimeException e) {
            inFlightCompiles = Math.max(0, inFlightCompiles - 1);
            throw e;
        }
        return true;
    }

    private void disposeSectionMesh(RenderSectionKey key) {
        ChunkMesh mesh = meshes.remove(key);
        if (mesh != null) {
            mesh.dispose();
        }
    }

    private Set<RenderSectionKey> getCachedTraversableSections(RenderSectionKey start, ChunkManager chunkManager) {
        if (start == null || !isTraversable(start, chunkManager)) {
            return null;
        }

        if (start.equals(cachedTraversalStart) && cachedTraversalRevision == visibilityRevision) {
            return cachedTraversableSections;
        }

        if (inFlightCompiles > 0) {
            return null;
        }

        cachedTraversalStart = start;
        cachedTraversalRevision = visibilityRevision;
        cachedTraversableSections = collectTraversableSections(start, chunkManager);
        return cachedTraversableSections;
    }

    private void invalidateTraversalCache() {
        visibilityRevision++;
        cachedTraversalRevision = -1;
        cachedTraversalStart = null;
        cachedTraversableSections = null;
    }

    private Set<RenderSectionKey> collectTraversableSections(RenderSectionKey start, ChunkManager chunkManager) {
        Set<TraversalState> visitedStates = new HashSet<>();
        Set<RenderSectionKey> visibleSections = new HashSet<>();
        ArrayDeque<TraversalState> queue = new ArrayDeque<>();
        TraversalState startState = new TraversalState(start, null);
        visitedStates.add(startState);
        queue.add(startState);

        while (!queue.isEmpty()) {
            TraversalState state = queue.removeFirst();
            visibleSections.add(state.key());

            SectionVisibility visibility = sectionVisibility.getOrDefault(
                state.key(),
                DEFAULT_VISIBILITY
            );
            for (SectionFace exitFace : SectionFace.values()) {
                if (state.entryFace() != null && !visibility.isVisible(state.entryFace(), exitFace)) {
                    continue;
                }

                RenderSectionKey next = offset(state.key(), exitFace);
                if (next == null || !isTraversable(next, chunkManager)) {
                    continue;
                }

                TraversalState nextState = new TraversalState(next, exitFace.opposite());
                if (visitedStates.add(nextState)) {
                    queue.add(nextState);
                }
            }
        }
        return visibleSections;
    }

    private RenderSectionKey getCameraSection(Camera camera) {
        if (camera == null || camera.position == null) {
            return null;
        }
        int sectionY = Chunk.getRenderSectionIndex((int) Math.floor(camera.position.y));
        if (!Chunk.isValidRenderSectionIndex(sectionY)) {
            return null;
        }

        return new RenderSectionKey(
            ChunkCoord.fromWorldPos(camera.position.x, camera.position.z, Chunk.CHUNK_SIZE),
            sectionY
        );
    }

    private RenderSectionKey offset(RenderSectionKey key, SectionFace face) {
        int sectionY = key.sectionY() + face.dy;
        if (!Chunk.isValidRenderSectionIndex(sectionY)) {
            return null;
        }

        ChunkCoord coord = key.chunkCoord();
        return new RenderSectionKey(
            new ChunkCoord(coord.x + face.dx, coord.z + face.dz),
            sectionY
        );
    }

    private boolean isNearCameraSection(RenderSectionKey key, RenderSectionKey cameraSection) {
        if (key == null || cameraSection == null) {
            return false;
        }

        ChunkCoord coord = key.chunkCoord();
        ChunkCoord cameraCoord = cameraSection.chunkCoord();
        int dx = Math.abs(coord.x - cameraCoord.x);
        int dz = Math.abs(coord.z - cameraCoord.z);
        int dy = Math.abs(key.sectionY() - cameraSection.sectionY());
        return dx <= 1 && dz <= 1 && dy <= 1;
    }

    private boolean isTraversable(RenderSectionKey key, ChunkManager chunkManager) {
        Chunk chunk = chunkManager.getChunk(key.chunkCoord());
        return chunk != null
            && chunk.isGenerated()
            && chunkManager.isChunkVisible(key.chunkCoord())
            && Chunk.isValidRenderSectionIndex(key.sectionY());
    }

    private record CompileBatchResult(
        Map<RenderSectionKey, Integer> versions,
        Map<RenderSectionKey, BlockMeshBuilder.CompiledSectionMesh> compiledSections
    ) {
    }

    private record CompiledSectionApplication(
        RenderSectionKey key,
        int version,
        BlockMeshBuilder.CompiledSectionMesh compiledSection
    ) {
    }

    private record MeshApplySummary(int applied, int discarded, long elapsedMs) {
    }

    private record TraversalState(RenderSectionKey key, SectionFace entryFace) {
    }

    private record LayerInstance(ModelInstance instance, BoundingBox bounds) {
    }
}
