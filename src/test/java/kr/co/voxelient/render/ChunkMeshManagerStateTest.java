package kr.co.voxelient.render;

import kr.co.voxelite.world.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChunkMeshManagerStateTest {
    @Test
    void fullyOccludedSectionKeepsVisibilityAndDoesNotNeedRepair() throws Exception {
        World world = new World(null);
        ChunkMeshManager manager = new ChunkMeshManager(world, null, null, null);
        try {
            Chunk center = new Chunk(new ChunkCoord(0, 0));
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 15; y <= 32; y++) center.addBlockLocal(x, y, z, 1);
                }
            }
            center.markAsGenerated();
            world.applyChunk(center);
            for (ChunkCoord coord : Set.of(new ChunkCoord(-1, 0), new ChunkCoord(1, 0),
                    new ChunkCoord(0, -1), new ChunkCoord(0, 1))) {
                Chunk neighbor = new Chunk(coord);
                for (int i = 0; i < 16; i++) {
                    for (int y = 16; y < 32; y++) {
                        int x = coord.x < 0 ? 15 : coord.x > 0 ? 0 : i;
                        int z = coord.z < 0 ? 15 : coord.z > 0 ? 0 : i;
                        neighbor.addBlockLocal(x, y, z, 1);
                    }
                }
                neighbor.markAsGenerated();
                world.applyChunk(neighbor);
            }

            BlockMeshBuilder builder = new BlockMeshBuilder(null, null);
            RenderSectionKey key = new RenderSectionKey(center.getCoord(), 1);
            var compiled = builder.compileSectionMeshes(builder.prepareSectionBuildInputs(
                center, world.getChunkManager(), Set.of(1))).get(key);
            assertTrue(compiled.mergedQuads().isEmpty());
            assertFalse(compiled.visibility().isVisible(SectionFace.WEST, SectionFace.EAST));

            Map<RenderSectionKey, Integer> versions = field(manager, "buildVersions");
            versions.put(key, 1);
            Class<?> applicationType = Class.forName(ChunkMeshManager.class.getName() + "$CompiledSectionApplication");
            Constructor<?> constructor = applicationType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object application = constructor.newInstance(key, 1, compiled);
            assertEquals(true, invoke(manager, "applyCompiledSection",
                new Class<?>[]{ChunkManager.class, applicationType}, world.getChunkManager(), application));

            Map<RenderSectionKey, SectionVisibility> visibility = field(manager, "sectionVisibility");
            assertSame(compiled.visibility(), visibility.get(key));
            Set<Integer> missing = invoke(manager, "findMissingMeshSections", new Class<?>[]{Chunk.class}, center);
            assertFalse(missing.contains(1));

            // A block edit must still invalidate the cached no-geometry section.
            while (world.getChunkManager().pollDirtySection() != null) {}
            world.removeBlock(new com.badlogic.gdx.math.Vector3(0, 16, 0));
            assertTrue(world.getChunkManager().drainDirtySections(center.getCoord()).contains(1));
            var exposed = builder.compileSectionMeshes(builder.prepareSectionBuildInputs(
                center, world.getChunkManager(), Set.of(1))).get(key);
            assertFalse(exposed.mergedQuads().isEmpty());
        } finally {
            manager.dispose();
            world.dispose();
        }
    }

    @Test
    void replacingOneSectionRequeuesOtherSectionsEvenWithAnOldMesh() throws Exception {
        World world = new World(null);
        ChunkMeshManager manager = new ChunkMeshManager(world, null, null, null);
        try {
            ChunkCoord coord = new ChunkCoord(0, 0);
            RenderSectionKey updated = new RenderSectionKey(coord, 0);
            RenderSectionKey sibling = new RenderSectionKey(coord, 1);
            Object task = invoke(manager, "createCompileTask", new Class<?>[]{ChunkCoord.class, Map.class},
                coord, Map.of(updated, 1, sibling, 1));
            Map<Long, Object> tasks = field(manager, "activeCompileTasks");
            tasks.put(1L, task);
            Map<RenderSectionKey, ChunkMesh> meshes = field(manager, "meshes");
            meshes.put(sibling, new ChunkMesh());

            invoke(manager, "cancelCompileTasksForSections", new Class<?>[]{Set.class}, Set.of(updated));

            assertEquals(true, invoke(task, "isCanceled", new Class<?>[]{}));
            assertEquals(Set.of(1), world.getChunkManager().drainDirtySections(coord));
            assertEquals(true, invoke(manager, "hasKnownRenderState", new Class<?>[]{RenderSectionKey.class}, sibling));

            // Repeated cancellation must not enqueue an already canceled batch again.
            invoke(manager, "cancelCompileTasksForSections", new Class<?>[]{Set.class}, Set.of(updated));
            assertNull(world.getChunkManager().pollDirtySection());
        } finally {
            manager.dispose();
            world.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return (T) method.invoke(target, args);
    }
}
