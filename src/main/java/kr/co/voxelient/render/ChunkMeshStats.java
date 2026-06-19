package kr.co.voxelient.render;

/**
 * Snapshot of chunk mesh workload for debugging frame drops.
 */
public record ChunkMeshStats(
    int meshSections,
    int visibilitySections,
    int inFlightCompiles,
    int completedCompileBatches,
    int pendingMeshApplications,
    int selectedDirtyChunks,
    int queuedCompileBatches,
    int appliedMeshSections,
    int discardedMeshSections,
    long meshApplyMs,
    boolean compileThrottled
) {
    public static ChunkMeshStats empty() {
        return new ChunkMeshStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, false);
    }
}
