package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import kr.co.voxelite.world.BlockManager;
import kr.co.voxelite.world.Chunk;
import kr.co.voxelite.world.ChunkCoord;
import kr.co.voxelite.world.ChunkManager;
import kr.co.voxelite.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Manages client-side chunk mesh cache.
 */
public class ChunkMeshManager {
    private final World world;
    private final BlockMeshBuilder meshBuilder;
    private final Map<ChunkCoord, ChunkMesh> meshes = new HashMap<>();

    public ChunkMeshManager(World world, String textureAtlasPath, BlockManager.IBlockTextureProvider textureProvider) {
        this.world = world;
        meshBuilder = new BlockMeshBuilder(textureAtlasPath, textureProvider);
    }

    public void processDirtyChunks(int maxPerFrame) {
        ChunkManager chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            return;
        }

        sweepStaleMeshes();

        int built = 0;
        while (built < maxPerFrame) {
            ChunkCoord coord = chunkManager.pollDirtyChunk();
            if (coord == null) {
                break;
            }

            Chunk chunk = chunkManager.getChunk(coord);
            if (chunk == null || !chunk.isGenerated()) {
                disposeMesh(coord);
                continue;
            }

            disposeMesh(coord);
            ChunkMesh mesh = meshBuilder.buildChunkMesh(chunk, chunkManager);
            if (mesh != null && mesh.hasInstance()) {
                meshes.put(coord, mesh);
            }
            built++;
        }
    }

    public List<ModelInstance> getVisibleInstances(Camera camera) {
        ChunkManager chunkManager = world.getChunkManager();
        List<ModelInstance> visibleInstances = new ArrayList<>();
        if (chunkManager == null) {
            return visibleInstances;
        }

        sweepStaleMeshes();

        for (Chunk chunk : chunkManager.getLoadedChunks()) {
            if (!chunk.isGenerated() || !chunkManager.isChunkVisible(chunk.getCoord())) {
                continue;
            }

            ChunkMesh mesh = meshes.get(chunk.getCoord());
            if (mesh == null || !mesh.hasInstance()) {
                continue;
            }

            if (camera == null || camera.frustum.boundsInFrustum(chunk.getBounds())) {
                visibleInstances.add(mesh.getInstance());
            }
        }
        return visibleInstances;
    }

    public void dispose() {
        for (ChunkMesh mesh : meshes.values()) {
            mesh.dispose();
        }
        meshes.clear();
        meshBuilder.dispose();
    }

    private void sweepStaleMeshes() {
        ChunkManager chunkManager = world.getChunkManager();
        if (chunkManager == null) {
            dispose();
            return;
        }

        Iterator<Map.Entry<ChunkCoord, ChunkMesh>> iterator = meshes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkCoord, ChunkMesh> entry = iterator.next();
            Chunk chunk = chunkManager.getChunk(entry.getKey());
            if (chunk == null || !chunk.isGenerated()) {
                entry.getValue().dispose();
                iterator.remove();
            }
        }
    }

    private void disposeMesh(ChunkCoord coord) {
        ChunkMesh mesh = meshes.remove(coord);
        if (mesh != null) {
            mesh.dispose();
        }
    }
}
