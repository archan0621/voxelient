package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.collision.BoundingBox;
import kr.co.voxelite.world.BlockRenderLayer;

import java.util.EnumMap;
import java.util.Map;

/**
 * Unified rendering mesh for a chunk.
 */
public class ChunkMesh {
    private final Map<BlockRenderLayer, Model> models = new EnumMap<>(BlockRenderLayer.class);
    private final Map<BlockRenderLayer, ModelInstance> instances = new EnumMap<>(BlockRenderLayer.class);
    private BoundingBox bounds;

    public void setModel(Model model) {
        setModel(BlockRenderLayer.SOLID, model);
    }

    public void setModel(BlockRenderLayer renderLayer, Model model) {
        BlockRenderLayer safeLayer = renderLayer != null ? renderLayer : BlockRenderLayer.SOLID;
        Model previous = models.remove(safeLayer);
        if (previous != null) {
            previous.dispose();
        }

        if (model == null) {
            instances.remove(safeLayer);
            return;
        }

        models.put(safeLayer, model);
        instances.put(safeLayer, new ModelInstance(model));
    }

    public ModelInstance getInstance() {
        return getInstance(BlockRenderLayer.SOLID);
    }

    public ModelInstance getInstance(BlockRenderLayer renderLayer) {
        return instances.get(renderLayer);
    }

    public void setBounds(BoundingBox bounds) {
        this.bounds = bounds != null ? new BoundingBox(bounds.min.cpy(), bounds.max.cpy()) : null;
    }

    public BoundingBox getBounds() {
        return bounds;
    }

    public boolean hasInstance() {
        return bounds != null && !instances.isEmpty();
    }

    public boolean hasInstance(BlockRenderLayer renderLayer) {
        return bounds != null && instances.containsKey(renderLayer);
    }

    public void clear() {
        for (Model model : models.values()) {
            model.dispose();
        }
        models.clear();
        instances.clear();
        bounds = null;
    }

    public void dispose() {
        clear();
    }
}
