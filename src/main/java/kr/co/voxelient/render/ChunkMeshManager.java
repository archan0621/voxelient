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
import java.util.EnumMap;
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
    private static final int MAX_SECTION_VISIBILITY_TRAVERSAL_MESHES = 512;
    private static final int MAX_MISSING_MESH_REPAIR_SCAN_CHUNKS = 96;
    private static final long DEFAULT_MESH_APPLY_BUDGET_MS = 4L;
    private static final SectionVisibility DEFAULT_VISIBILITY = SectionVisibility.allVisible();

    private final World world;
    private final BlockMeshBuilder meshBuilder;
    private final Map<RenderSectionKey, ChunkMesh> meshes = new HashMap<>();
    private final Map<RenderSectionKey, SectionVisibility> sectionVisibility = new HashMap<>();
    private final Map<RenderSectionKey, Integer> buildVersions = new HashMap<>();
    private final Set<RenderSectionKey> emptySections = new HashSet<>();
    private final Map<ChunkCoord, Set<Integer>> deferredDirtySectionsByChunk = new HashMap<>();
    private final Map<Long, RenderCompileTask> activeCompileTasks = new HashMap<>();
    private final Queue<CompileBatchResult> completedCompiles = new ConcurrentLinkedQueue<>();
    private final Queue<CompiledSectionApplication> pendingMeshApplications = new ConcurrentLinkedQueue<>();
    private final ExecutorService compileExecutor = Executors.newFixedThreadPool(2);
    private long nextCompileTaskId = 1L;
    private int inFlightCompiles = 0;
    private int visibilityRevision = 0;
    private int cachedTraversalRevision = -1;
    private RenderSectionKey cachedTraversalStart = null;
    private Set<RenderSectionKey> cachedTraversableSections = null;
    private ChunkMeshStats stats = ChunkMeshStats.empty();
    private int frameCanceledCompileTasks = 0;
    private int frameStaleCompileResults = 0;

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
        long t0 = PerformanceLogger.now();
        ChunkManager chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            return;
        }
        frameCanceledCompileTasks = 0;
        frameStaleCompileResults = 0;

        MeshApplySummary applySummary = applyPendingMeshApplications(
            chunkManager,
            RenderFrameBudget.of(maxMeshApplicationsPerFrame, meshApplyBudgetMs)
        );
        int completedBatches = drainCompletedCompiles(MAX_COMPLETED_BATCH_DRAINS_PER_FRAME, chunkManager);
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
            selectedChunks += drainVisibleDeferredSections(
                dirtySectionsByChunk,
                chunkManager,
                availableCompileSlots
            );

            int polledChunks = 0;
            while (selectedChunks < availableCompileSlots && polledChunks < availableCompileSlots) {
                RenderSectionKey key = chunkManager.pollDirtySection();
                if (key == null) {
                    break;
                }

                ChunkCoord coord = key.chunkCoord();
                Set<Integer> sections = new HashSet<>();
                sections.add(key.sectionY());
                sections.addAll(chunkManager.drainDirtySections(coord));
                polledChunks++;

                Chunk chunk = chunkManager.getChunk(coord);
                if (chunk == null || !chunk.isGenerated()) {
                    removeDeferredSections(coord);
                    disposeChunkMeshes(coord);
                    continue;
                }
                if (!chunkManager.isChunkVisible(coord)) {
                    deferDirtySections(coord, sections);
                    disposeChunkMeshes(coord);
                    continue;
                }

                dirtySectionsByChunk.put(coord, sections);
                selectedChunks++;
            }

            selectedChunks += repairMissingVisibleMeshes(
                dirtySectionsByChunk,
                chunkManager,
                availableCompileSlots - selectedChunks
            );

            for (Map.Entry<ChunkCoord, Set<Integer>> entry : dirtySectionsByChunk.entrySet()) {
                ChunkCoord coord = entry.getKey();
                Chunk chunk = chunkManager.getChunk(coord);
                if (chunk == null || !chunk.isGenerated() || !chunkManager.isChunkVisible(coord)) {
                    if (chunk != null && chunk.isGenerated()) {
                        deferDirtySections(coord, entry.getValue());
                    } else {
                        removeDeferredSections(coord);
                    }
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
            deferredDirtySectionsByChunk.size(),
            countDeferredDirtySections(),
            selectedChunks,
            queuedCompileBatches,
            applySummary.applied(),
            applySummary.discarded(),
            frameCanceledCompileTasks,
            frameStaleCompileResults,
            applySummary.elapsedMs(),
            compileThrottled
        );
        logMeshProcess(
            t0,
            completedBatches,
            selectedChunks,
            queuedCompileBatches,
            applySummary,
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

    private void logMeshProcess(
        long startMs,
        int completedBatches,
        int selectedChunks,
        int queuedCompileBatches,
        MeshApplySummary applySummary,
        boolean compileThrottled
    ) {
        if (!PerformanceLogger.ENABLED) {
            return;
        }

        long elapsedMs = PerformanceLogger.now() - startMs;
        if (!PerformanceLogger.shouldLogSlow(elapsedMs, PerformanceLogger.SLOW_MESH_PROCESS_MS)
            && !PerformanceLogger.shouldLogSlow(applySummary.elapsedMs(), PerformanceLogger.SLOW_MESH_PROCESS_MS)
            && !PerformanceLogger.shouldLogInterval()) {
            return;
        }

        System.out.printf(
            "[PERF][ChunkMeshManager] process=%dms apply=%d/%d discarded=%d completed=%d selected=%d queued=%d active=%d pending=%d deferred=%d canceled=%d stale=%d%s%n",
            elapsedMs,
            applySummary.applied(),
            applySummary.elapsedMs(),
            applySummary.discarded(),
            completedBatches,
            selectedChunks,
            queuedCompileBatches,
            inFlightCompiles,
            pendingMeshApplications.size(),
            countDeferredDirtySections(),
            frameCanceledCompileTasks,
            frameStaleCompileResults,
            compileThrottled ? " throttled" : ""
        );
    }

    private int drainCompletedCompiles(int maxBatches, ChunkManager chunkManager) {
        int drained = 0;
        CompileBatchResult result;
        while (drained < maxBatches
            && pendingMeshApplications.size() < MAX_PENDING_MESH_APPLICATIONS
            && (result = completedCompiles.poll()) != null) {
            RenderCompileTask task = result.task();
            finishCompileTask(task);
            drained++;

            if (task.isCanceled()) {
                continue;
            }
            if (!isTaskRenderable(task, chunkManager)) {
                task.cancel();
                frameStaleCompileResults += task.sectionCount();
                if (!isChunkGenerated(task.coord(), chunkManager)) {
                    removeBuildVersions(task.versions().keySet());
                    removeDeferredSections(task.coord());
                } else if (!chunkManager.isChunkVisible(task.coord())) {
                    deferDirtySections(task.coord(), task.sections());
                }
                continue;
            }

            for (Map.Entry<RenderSectionKey, Integer> versionEntry : task.versions().entrySet()) {
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
            frameStaleCompileResults++;
            return false;
        }

        Chunk chunk = chunkManager.getChunk(key.chunkCoord());
        if (chunk == null || !chunk.isGenerated()) {
            disposeSectionMesh(key);
            sectionVisibility.remove(key);
            buildVersions.remove(key);
            emptySections.remove(key);
            removeDeferredSections(key.chunkCoord());
            invalidateTraversalCache();
            return false;
        }
        if (!chunkManager.isChunkVisible(key.chunkCoord())) {
            disposeSectionMesh(key);
            sectionVisibility.remove(key);
            buildVersions.remove(key);
            emptySections.remove(key);
            deferDirtySection(key);
            invalidateTraversalCache();
            frameStaleCompileResults++;
            return false;
        }

        BlockMeshBuilder.CompiledSectionMesh compiledSection = application.compiledSection();
        if (compiledSection == null) {
            disposeSectionMesh(key);
            sectionVisibility.remove(key);
            emptySections.add(key);
            invalidateTraversalCache();
            return true;
        }

        sectionVisibility.put(key, compiledSection.visibility());
        emptySections.remove(key);
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
            boolean missing = chunk == null || !chunk.isGenerated();
            boolean invisible = !missing && !chunkManager.isChunkVisible(entry.getKey().chunkCoord());
            if (missing || invisible) {
                entry.getValue().dispose();
                sectionVisibility.remove(entry.getKey());
                buildVersions.remove(entry.getKey());
                emptySections.remove(entry.getKey());
                iterator.remove();
                if (invisible) {
                    deferDirtySection(entry.getKey());
                } else {
                    removeDeferredSections(entry.getKey().chunkCoord());
                }
                removedMesh = true;
            }
        }

        boolean removedVisibility = sectionVisibility.keySet().removeIf(key -> {
            Chunk chunk = chunkManager.getChunk(key.chunkCoord());
            return chunk == null || !chunk.isGenerated() || !chunkManager.isChunkVisible(key.chunkCoord());
        });
        boolean removedEmptySections = emptySections.removeIf(key -> {
            Chunk chunk = chunkManager.getChunk(key.chunkCoord());
            return chunk == null || !chunk.isGenerated() || !chunkManager.isChunkVisible(key.chunkCoord());
        });
        if (removedMesh || removedVisibility || removedEmptySections) {
            invalidateTraversalCache();
        }
    }

    public List<ModelInstance> getVisibleInstances(Camera camera) {
        List<ModelInstance> visibleInstances = new ArrayList<>();
        Map<BlockRenderLayer, List<ModelInstance>> instancesByLayer = getVisibleInstancesByLayer(camera);
        for (BlockRenderLayer renderLayer : BlockRenderLayer.values()) {
            visibleInstances.addAll(instancesByLayer.get(renderLayer));
        }
        return visibleInstances;
    }

    public List<ModelInstance> getVisibleInstances(Camera camera, BlockRenderLayer renderLayer) {
        if (renderLayer == null) {
            return List.of();
        }
        return getVisibleInstancesByLayer(camera).get(renderLayer);
    }

    public Map<BlockRenderLayer, List<ModelInstance>> getVisibleInstancesByLayer(Camera camera) {
        long t0 = PerformanceLogger.now();
        ChunkManager chunkManager = world.getChunkManager();
        Map<BlockRenderLayer, List<LayerInstance>> visibleInstancesByLayer = createLayerBuckets();
        if (chunkManager == null) {
            return toModelInstanceBuckets(visibleInstancesByLayer);
        }

        RenderSectionKey cameraSection = getCameraSection(camera);
        Set<RenderSectionKey> traversableSections = cameraSection != null
            ? getCachedTraversableSections(cameraSection, chunkManager)
            : null;
        int traversableCount = traversableSections != null ? traversableSections.size() : -1;
        int scanned = 0;
        int chunkCulled = 0;
        int traversalCulled = 0;
        int frustumCulled = 0;
        int visibleSections = 0;

        for (Map.Entry<RenderSectionKey, ChunkMesh> entry : meshes.entrySet()) {
            scanned++;
            RenderSectionKey key = entry.getKey();
            Chunk chunk = chunkManager.getChunk(key.chunkCoord());
            if (chunk == null || !chunk.isGenerated() || !chunkManager.isChunkVisible(key.chunkCoord())) {
                chunkCulled++;
                continue;
            }
            if (traversableSections != null
                && !traversableSections.contains(key)
                && !isNearCameraSection(key, cameraSection)) {
                traversalCulled++;
                continue;
            }

            ChunkMesh mesh = entry.getValue();
            if (mesh == null || !mesh.hasInstance()) {
                chunkCulled++;
                continue;
            }

            boolean nearCameraSection = isNearCameraSection(key, cameraSection);
            if (camera == null || nearCameraSection || camera.frustum.boundsInFrustum(mesh.getBounds())) {
                visibleSections++;
                addVisibleLayerInstances(visibleInstancesByLayer, mesh);
            } else {
                frustumCulled++;
            }
        }

        List<LayerInstance> translucentInstances = visibleInstancesByLayer.get(BlockRenderLayer.TRANSLUCENT);
        if (camera != null && !translucentInstances.isEmpty()) {
            visibleInstancesByLayer.put(BlockRenderLayer.TRANSLUCENT, TranslucentRenderOrder.backToFront(
                camera,
                translucentInstances,
                LayerInstance::bounds
            ));
        }

        logVisibleCollection(
            t0,
            scanned,
            visibleSections,
            chunkCulled,
            traversalCulled,
            frustumCulled,
            traversableCount,
            visibleInstancesByLayer
        );
        return toModelInstanceBuckets(visibleInstancesByLayer);
    }

    private Map<BlockRenderLayer, List<LayerInstance>> createLayerBuckets() {
        Map<BlockRenderLayer, List<LayerInstance>> buckets = new EnumMap<>(BlockRenderLayer.class);
        for (BlockRenderLayer renderLayer : BlockRenderLayer.values()) {
            buckets.put(renderLayer, new ArrayList<>());
        }
        return buckets;
    }

    private void addVisibleLayerInstances(
        Map<BlockRenderLayer, List<LayerInstance>> visibleInstancesByLayer,
        ChunkMesh mesh
    ) {
        for (BlockRenderLayer renderLayer : BlockRenderLayer.values()) {
            if (mesh.hasInstance(renderLayer)) {
                visibleInstancesByLayer.get(renderLayer).add(
                    new LayerInstance(mesh.getInstance(renderLayer), mesh.getBounds())
                );
            }
        }
    }

    private Map<BlockRenderLayer, List<ModelInstance>> toModelInstanceBuckets(
        Map<BlockRenderLayer, List<LayerInstance>> layerInstancesByLayer
    ) {
        Map<BlockRenderLayer, List<ModelInstance>> buckets = new EnumMap<>(BlockRenderLayer.class);
        for (BlockRenderLayer renderLayer : BlockRenderLayer.values()) {
            List<LayerInstance> layerInstances = layerInstancesByLayer.get(renderLayer);
            List<ModelInstance> modelInstances = new ArrayList<>(layerInstances.size());
            for (LayerInstance layerInstance : layerInstances) {
                modelInstances.add(layerInstance.instance());
            }
            buckets.put(renderLayer, modelInstances);
        }
        return buckets;
    }

    private void logVisibleCollection(
        long startMs,
        int scanned,
        int visibleSections,
        int chunkCulled,
        int traversalCulled,
        int frustumCulled,
        int traversableCount,
        Map<BlockRenderLayer, List<LayerInstance>> visibleInstancesByLayer
    ) {
        if (!PerformanceLogger.ENABLED) {
            return;
        }

        long elapsedMs = PerformanceLogger.now() - startMs;
        int solid = visibleInstancesByLayer.get(BlockRenderLayer.SOLID).size();
        int cutout = visibleInstancesByLayer.get(BlockRenderLayer.CUTOUT).size();
        int translucent = visibleInstancesByLayer.get(BlockRenderLayer.TRANSLUCENT).size();
        int totalInstances = solid + cutout + translucent;
        if (!PerformanceLogger.shouldLogSlow(elapsedMs, PerformanceLogger.SLOW_COLLECT_MS)
            && !PerformanceLogger.shouldLogInterval()) {
            return;
        }

        System.out.printf(
            "[PERF][ChunkMeshManager] collect=%dms scanned=%d visibleSections=%d instances=%d solid=%d cutout=%d translucent=%d cull(chunk=%d traversal=%d frustum=%d) traversable=%d mesh=%d active=%d pending=%d%n",
            elapsedMs,
            scanned,
            visibleSections,
            totalInstances,
            solid,
            cutout,
            translucent,
            chunkCulled,
            traversalCulled,
            frustumCulled,
            traversableCount,
            meshes.size(),
            inFlightCompiles,
            pendingMeshApplications.size()
        );
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
        emptySections.clear();
        deferredDirtySectionsByChunk.clear();
        activeCompileTasks.clear();
        completedCompiles.clear();
        pendingMeshApplications.clear();
        stats = ChunkMeshStats.empty();
        meshBuilder.dispose();
        inFlightCompiles = 0;
        invalidateTraversalCache();
    }

    private void disposeChunkMeshes(ChunkCoord coord) {
        cancelCompileTasks(coord);

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
            emptySections.remove(entry.getKey());
            iterator.remove();
            removedMesh = true;
        }

        boolean removedVisibility = sectionVisibility.keySet().removeIf(key -> key.chunkCoord().equals(coord));
        boolean removedBuildVersions = buildVersions.keySet().removeIf(key -> key.chunkCoord().equals(coord));
        boolean removedEmptySections = emptySections.removeIf(key -> key.chunkCoord().equals(coord));
        if (removedMesh || removedVisibility || removedBuildVersions || removedEmptySections) {
            invalidateTraversalCache();
        }
    }

    private int drainVisibleDeferredSections(
        Map<ChunkCoord, Set<Integer>> target,
        ChunkManager chunkManager,
        int maxChunks
    ) {
        if (deferredDirtySectionsByChunk.isEmpty() || maxChunks <= 0) {
            return 0;
        }

        int drained = 0;
        Iterator<Map.Entry<ChunkCoord, Set<Integer>>> iterator = deferredDirtySectionsByChunk.entrySet().iterator();
        while (iterator.hasNext() && drained < maxChunks) {
            Map.Entry<ChunkCoord, Set<Integer>> entry = iterator.next();
            ChunkCoord coord = entry.getKey();
            Chunk chunk = chunkManager.getChunk(coord);
            if (chunk == null || !chunk.isGenerated()) {
                iterator.remove();
                continue;
            }
            if (!chunkManager.isChunkVisible(coord)) {
                continue;
            }

            target.put(coord, new HashSet<>(entry.getValue()));
            iterator.remove();
            drained++;
        }
        return drained;
    }

    private int repairMissingVisibleMeshes(
        Map<ChunkCoord, Set<Integer>> target,
        ChunkManager chunkManager,
        int maxChunks
    ) {
        if (maxChunks <= 0) {
            return 0;
        }

        int repaired = 0;
        for (Chunk chunk : chunkManager.getVisibleGeneratedChunksByDistance(MAX_MISSING_MESH_REPAIR_SCAN_CHUNKS)) {
            if (repaired >= maxChunks) {
                break;
            }

            ChunkCoord coord = chunk.getCoord();
            if (target.containsKey(coord)) {
                continue;
            }

            Set<Integer> missingSections = findMissingMeshSections(chunk);
            if (missingSections.isEmpty()) {
                continue;
            }

            missingSections.addAll(chunkManager.drainDirtySections(coord));
            target.put(coord, missingSections);
            repaired++;
        }
        return repaired;
    }

    private Set<Integer> findMissingMeshSections(Chunk chunk) {
        Set<Integer> missingSections = new HashSet<>();
        if (chunk == null || !chunk.isGenerated()) {
            return missingSections;
        }

        ChunkCoord coord = chunk.getCoord();
        for (Chunk.BlockData block : chunk.getBlocks()) {
            int sectionY = Chunk.getRenderSectionIndex(block.pos.y());
            if (!Chunk.isValidRenderSectionIndex(sectionY)) {
                continue;
            }

            RenderSectionKey key = new RenderSectionKey(coord, sectionY);
            if (hasKnownRenderState(key)) {
                continue;
            }
            missingSections.add(sectionY);
        }
        return missingSections;
    }

    private boolean hasKnownRenderState(RenderSectionKey key) {
        return meshes.containsKey(key)
            || emptySections.contains(key)
            || isSectionPendingApplication(key)
            || isSectionInActiveCompile(key);
    }

    private boolean isSectionPendingApplication(RenderSectionKey key) {
        for (CompiledSectionApplication application : pendingMeshApplications) {
            if (application.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSectionInActiveCompile(RenderSectionKey key) {
        for (RenderCompileTask task : activeCompileTasks.values()) {
            if (!task.isCanceled() && task.versions().containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private void deferDirtySection(RenderSectionKey key) {
        if (key != null) {
            deferDirtySections(key.chunkCoord(), Set.of(key.sectionY()));
        }
    }

    private void deferDirtySections(ChunkCoord coord, Set<Integer> sections) {
        if (coord == null || sections == null || sections.isEmpty()) {
            return;
        }

        Set<Integer> deferredSections = deferredDirtySectionsByChunk.computeIfAbsent(coord, ignored -> new HashSet<>());
        for (Integer sectionY : sections) {
            if (sectionY != null && Chunk.isValidRenderSectionIndex(sectionY)) {
                deferredSections.add(sectionY);
            }
        }
        if (deferredSections.isEmpty()) {
            deferredDirtySectionsByChunk.remove(coord);
        }
    }

    private void removeDeferredSections(ChunkCoord coord) {
        if (coord != null) {
            deferredDirtySectionsByChunk.remove(coord);
        }
    }

    private int countDeferredDirtySections() {
        int count = 0;
        for (Set<Integer> sections : deferredDirtySectionsByChunk.values()) {
            count += sections.size();
        }
        return count;
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
            emptySections.remove(key);
            versions.put(key, version);
        }

        cancelCompileTasksForSections(versions.keySet());
        RenderCompileTask task = createCompileTask(coord, versions);
        activeCompileTasks.put(task.id(), task);
        inFlightCompiles = activeCompileTasks.size();
        try {
            compileExecutor.submit(() -> {
                completedCompiles.offer(runCompileTask(task, sectionInputs, chunkManager));
            });
        } catch (RuntimeException e) {
            activeCompileTasks.remove(task.id());
            inFlightCompiles = activeCompileTasks.size();
            throw e;
        }
        return true;
    }

    private CompileBatchResult runCompileTask(
        RenderCompileTask task,
        Map<RenderSectionKey, BlockMeshBuilder.SectionBuildInput> sectionInputs,
        ChunkManager chunkManager
    ) {
        Map<RenderSectionKey, BlockMeshBuilder.CompiledSectionMesh> compiledSections = Map.of();
        try {
            if (!task.start() || !isChunkVisibleAndGenerated(task.coord(), chunkManager)) {
                task.cancel();
                return new CompileBatchResult(task, compiledSections);
            }

            compiledSections = meshBuilder.compileSectionMeshes(sectionInputs);
            if (!task.isCanceled()) {
                task.markUploading();
            }
        } catch (Exception e) {
            e.printStackTrace();
            task.cancel();
            compiledSections = Map.of();
        }
        return new CompileBatchResult(task, Map.copyOf(compiledSections));
    }

    private RenderCompileTask createCompileTask(ChunkCoord coord, Map<RenderSectionKey, Integer> versions) {
        Set<Integer> sections = new HashSet<>();
        for (RenderSectionKey key : versions.keySet()) {
            sections.add(key.sectionY());
        }
        return new RenderCompileTask(nextCompileTaskId++, coord, Set.copyOf(sections), Map.copyOf(versions));
    }

    private void finishCompileTask(RenderCompileTask task) {
        if (task != null && activeCompileTasks.remove(task.id()) != null) {
            task.markDone();
        }
        inFlightCompiles = activeCompileTasks.size();
    }

    private void cancelCompileTasks(ChunkCoord coord) {
        if (coord == null) {
            return;
        }
        for (RenderCompileTask task : activeCompileTasks.values()) {
            if (task.coord().equals(coord) && task.cancel()) {
                frameCanceledCompileTasks++;
            }
        }
    }

    private void cancelCompileTasksForSections(Set<RenderSectionKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (RenderCompileTask task : activeCompileTasks.values()) {
            if (task.containsAny(keys) && task.cancel()) {
                frameCanceledCompileTasks++;
            }
        }
    }

    private boolean isTaskRenderable(RenderCompileTask task, ChunkManager chunkManager) {
        return task != null
            && !task.isCanceled()
            && isChunkVisibleAndGenerated(task.coord(), chunkManager)
            && areTaskVersionsCurrent(task);
    }

    private boolean areTaskVersionsCurrent(RenderCompileTask task) {
        for (Map.Entry<RenderSectionKey, Integer> entry : task.versions().entrySet()) {
            Integer expectedVersion = buildVersions.get(entry.getKey());
            if (expectedVersion == null || !expectedVersion.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean isChunkGenerated(ChunkCoord coord, ChunkManager chunkManager) {
        Chunk chunk = chunkManager.getChunk(coord);
        return chunk != null && chunk.isGenerated();
    }

    private boolean isChunkVisibleAndGenerated(ChunkCoord coord, ChunkManager chunkManager) {
        return isChunkGenerated(coord, chunkManager) && chunkManager.isChunkVisible(coord);
    }

    private void removeBuildVersions(Set<RenderSectionKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (RenderSectionKey key : keys) {
            buildVersions.remove(key);
            emptySections.remove(key);
        }
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

        if (shouldSkipSectionVisibilityTraversal()) {
            return null;
        }

        cachedTraversalStart = start;
        cachedTraversalRevision = visibilityRevision;
        cachedTraversableSections = collectTraversableSections(start, chunkManager);
        return cachedTraversableSections;
    }

    private boolean shouldSkipSectionVisibilityTraversal() {
        return inFlightCompiles > 0 || meshes.size() > MAX_SECTION_VISIBILITY_TRAVERSAL_MESHES;
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
        RenderCompileTask task,
        Map<RenderSectionKey, BlockMeshBuilder.CompiledSectionMesh> compiledSections
    ) {
    }

    private enum CompileTaskStatus {
        PENDING,
        COMPILING,
        UPLOADING,
        DONE,
        CANCELED
    }

    private static class RenderCompileTask {
        private final long id;
        private final ChunkCoord coord;
        private final Set<Integer> sections;
        private final Map<RenderSectionKey, Integer> versions;
        private volatile CompileTaskStatus status = CompileTaskStatus.PENDING;

        private RenderCompileTask(
            long id,
            ChunkCoord coord,
            Set<Integer> sections,
            Map<RenderSectionKey, Integer> versions
        ) {
            this.id = id;
            this.coord = coord;
            this.sections = sections;
            this.versions = versions;
        }

        private long id() {
            return id;
        }

        private ChunkCoord coord() {
            return coord;
        }

        private Set<Integer> sections() {
            return sections;
        }

        private Map<RenderSectionKey, Integer> versions() {
            return versions;
        }

        private int sectionCount() {
            return sections.size();
        }

        private synchronized boolean start() {
            if (status != CompileTaskStatus.PENDING) {
                return false;
            }
            status = CompileTaskStatus.COMPILING;
            return true;
        }

        private synchronized boolean cancel() {
            if (status == CompileTaskStatus.DONE || status == CompileTaskStatus.CANCELED) {
                return false;
            }
            status = CompileTaskStatus.CANCELED;
            return true;
        }

        private void markUploading() {
            if (!isCanceled()) {
                status = CompileTaskStatus.UPLOADING;
            }
        }

        private void markDone() {
            if (!isCanceled()) {
                status = CompileTaskStatus.DONE;
            }
        }

        private boolean isCanceled() {
            return status == CompileTaskStatus.CANCELED;
        }

        private boolean containsAny(Set<RenderSectionKey> keys) {
            for (RenderSectionKey key : keys) {
                if (versions.containsKey(key)) {
                    return true;
                }
            }
            return false;
        }
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
