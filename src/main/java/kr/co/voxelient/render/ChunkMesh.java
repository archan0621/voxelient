package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.collision.BoundingBox;

/**
 * Unified rendering mesh for a chunk.
 */
public class ChunkMesh {
    private Model model;
    private ModelInstance instance;
    private BoundingBox bounds;

    public void setModel(Model model) {
        if (this.model != null) {
            this.model.dispose();
        }

        this.model = model;
        instance = model != null ? new ModelInstance(model) : null;
    }

    public ModelInstance getInstance() {
        return instance;
    }

    public void setBounds(BoundingBox bounds) {
        this.bounds = bounds != null ? new BoundingBox(bounds.min.cpy(), bounds.max.cpy()) : null;
    }

    public BoundingBox getBounds() {
        return bounds;
    }

    public boolean hasInstance() {
        return instance != null && bounds != null;
    }

    public void clear() {
        if (model != null) {
            model.dispose();
            model = null;
        }
        instance = null;
        bounds = null;
    }

    public void dispose() {
        clear();
    }
}
