package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;

/**
 * Unified rendering mesh for a chunk.
 */
public class ChunkMesh {
    private Model model;
    private ModelInstance instance;

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

    public boolean hasInstance() {
        return instance != null;
    }

    public void clear() {
        if (model != null) {
            model.dispose();
            model = null;
        }
        instance = null;
    }

    public void dispose() {
        clear();
    }
}
