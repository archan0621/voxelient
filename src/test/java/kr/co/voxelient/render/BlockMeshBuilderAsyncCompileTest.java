package kr.co.voxelient.render;

import kr.co.voxelite.world.Chunk;
import kr.co.voxelite.world.ChunkCoord;
import kr.co.voxelite.world.ChunkManager;
import kr.co.voxelite.world.IChunkGenerator;
import kr.co.voxelite.world.IChunkLoadPolicy;
import kr.co.voxelite.world.RenderSectionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockMeshBuilderAsyncCompileTest {

    @TempDir
    Path tempDir;

    @Test
    void prepareAndCompileSectionMeshes_ShouldKeepRequestedSectionsAndGenerateQuads() {
        ChunkManager chunkManager = createChunkManager();
        Chunk chunk = new Chunk(new ChunkCoord(0, 0));
        chunk.addBlockLocal(0, 0f, 0, 1);
        chunk.addBlockLocal(1, 20f, 1, 2);
        chunk.markAsGenerated();
        chunkManager.replaceChunk(chunk);

        BlockMeshBuilder builder = new BlockMeshBuilder(null, null);
        Map<RenderSectionKey, BlockMeshBuilder.SectionBuildInput> inputs =
            builder.prepareSectionBuildInputs(chunk, chunkManager, Set.of(0, 1));

        assertEquals(Set.of(
            new RenderSectionKey(new ChunkCoord(0, 0), 0),
            new RenderSectionKey(new ChunkCoord(0, 0), 1)
        ), inputs.keySet());
        assertEquals(1, inputs.get(new RenderSectionKey(new ChunkCoord(0, 0), 0)).blocks().size());
        assertEquals(1, inputs.get(new RenderSectionKey(new ChunkCoord(0, 0), 1)).blocks().size());

        Map<RenderSectionKey, BlockMeshBuilder.CompiledSectionMesh> compiled =
            builder.compileSectionMeshes(inputs);

        assertFalse(compiled.get(new RenderSectionKey(new ChunkCoord(0, 0), 0)).mergedQuads().isEmpty());
        assertFalse(compiled.get(new RenderSectionKey(new ChunkCoord(0, 0), 1)).mergedQuads().isEmpty());
    }

    @Test
    void prepareSectionBuildInputs_ShouldReturnEmptyInputForNowEmptySection() {
        ChunkManager chunkManager = createChunkManager();
        Chunk chunk = new Chunk(new ChunkCoord(0, 0));
        chunk.addBlockLocal(0, 0f, 0, 1);
        chunk.markAsGenerated();
        chunkManager.replaceChunk(chunk);

        BlockMeshBuilder builder = new BlockMeshBuilder(null, null);
        Map<RenderSectionKey, BlockMeshBuilder.SectionBuildInput> inputs =
            builder.prepareSectionBuildInputs(chunk, chunkManager, Set.of(0, 1));

        assertTrue(inputs.containsKey(new RenderSectionKey(new ChunkCoord(0, 0), 1)));
        assertTrue(inputs.get(new RenderSectionKey(new ChunkCoord(0, 0), 1)).blocks().isEmpty());
    }

    private ChunkManager createChunkManager() {
        IChunkGenerator generator = (chunk, blockType) -> {
        };
        IChunkLoadPolicy loadPolicy = new IChunkLoadPolicy() {
            @Override
            public boolean shouldLoadToMemory(int chunkX, int chunkZ, int playerChunkX, int playerChunkZ) {
                return false;
            }

            @Override
            public boolean shouldKeepLoaded(int chunkX, int chunkZ, int playerChunkX, int playerChunkZ) {
                return false;
            }

            @Override
            public boolean shouldPregenerate(int chunkX, int chunkZ, int playerChunkX, int playerChunkZ) {
                return false;
            }

            @Override
            public int getMaxLoadedChunks() {
                return 16;
            }
        };
        return new ChunkManager(tempDir.toString(), 0, generator, loadPolicy);
    }
}
