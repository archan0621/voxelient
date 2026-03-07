package kr.co.voxelient.engine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import kr.co.voxelient.camera.CameraController;
import kr.co.voxelient.camera.FPSCamera;
import kr.co.voxelient.input.InputHandler;
import kr.co.voxelient.render.ChunkMeshManager;
import kr.co.voxelient.render.Renderer;
import kr.co.voxelite.engine.VoxeliteEngine;
import kr.co.voxelite.physics.RayCaster;
import kr.co.voxelite.physics.RaycastHit;

/**
 * Client facade for rendering, camera and input.
 */
public class VoxelientEngine {
    private final VoxeliteEngine coreEngine;
    private final VoxelientConfig config;

    private FPSCamera camera;
    private InputHandler input;
    private CameraController cameraController;
    private Renderer renderer;
    private ChunkMeshManager chunkMeshManager;

    private Vector3 selectedBlock;
    private RaycastHit raycastHit;
    private int screenWidth;
    private int screenHeight;
    private boolean initialized = false;

    public VoxelientEngine(VoxeliteEngine coreEngine, VoxelientConfig config) {
        this.coreEngine = coreEngine;
        this.config = config;
    }

    public void initialize(int screenWidth, int screenHeight) {
        if (initialized) {
            return;
        }
        if (!coreEngine.isInitialized()) {
            coreEngine.initialize();
        }

        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;

        camera = new FPSCamera(config.fieldOfView, screenWidth, screenHeight);
        camera.setPitch(config.initialPitch);
        camera.setFar(config.cameraFar);

        input = new InputHandler();
        input.getMouseHandler().setMouseSensitivity(config.mouseSensitivity);

        cameraController = new CameraController(camera, coreEngine.getPlayer(), coreEngine.getPhysics(), input);
        cameraController.setMoveSpeed(config.playerMoveSpeed);

        chunkMeshManager = new ChunkMeshManager(coreEngine.getWorld(), config.textureAtlasPath, config.textureProvider);
        renderer = new Renderer(screenWidth, screenHeight);
        initialized = true;
    }

    public void update(float delta) {
        ensureInitialized();
        float safeDelta = normalizeDelta(delta);

        input.update(safeDelta);
        cameraController.update(safeDelta);
        if (config.updateCoreEngine) {
            coreEngine.update(safeDelta);
        }
        chunkMeshManager.processDirtyChunks(config.chunkMeshBuildPerFrame);
        updateRaycast();
    }

    public void render() {
        ensureInitialized();
        renderer.render(camera, chunkMeshManager, screenWidth, screenHeight, selectedBlock, coreEngine.getPlayer().getPosition());
    }

    public void resize(int width, int height) {
        ensureInitialized();
        screenWidth = width;
        screenHeight = height;
        camera.resize(width, height);
        renderer.resize(width, height);
    }

    public void dispose() {
        if (renderer != null) {
            renderer.dispose();
        }
        if (chunkMeshManager != null) {
            chunkMeshManager.dispose();
        }
        initialized = false;
    }

    public FPSCamera getCamera() {
        return camera;
    }

    public InputHandler getInput() {
        return input;
    }

    public Vector3 getSelectedBlock() {
        return selectedBlock;
    }

    public RaycastHit getRaycastHit() {
        return raycastHit;
    }

    public VoxeliteEngine getCoreEngine() {
        return coreEngine;
    }

    public void setCameraController(CameraController controller) {
        cameraController = controller;
    }

    public void addBlock(Vector3 position, int blockType) {
        coreEngine.addBlock(position, blockType);
    }

    public void addBlock(Vector3 position) {
        coreEngine.addBlock(position);
    }

    public boolean removeBlock(Vector3 position) {
        return coreEngine.removeBlock(position);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public static Builder builder(VoxeliteEngine coreEngine) {
        return new Builder(coreEngine);
    }

    private void updateRaycast() {
        float centerX = Gdx.graphics != null ? Gdx.graphics.getWidth() / 2f : screenWidth / 2f;
        float centerY = Gdx.graphics != null ? Gdx.graphics.getHeight() / 2f : screenHeight / 2f;
        Ray ray = camera.getCamera().getPickRay(centerX, centerY);
        raycastHit = RayCaster.raycastWithFace(ray, coreEngine.getWorld());
        selectedBlock = raycastHit != null ? raycastHit.getBlockPosition() : null;
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("VoxelientEngine not initialized. Call initialize() first.");
        }
    }

    private float normalizeDelta(float delta) {
        if (!Float.isFinite(delta) || delta < 0f) {
            return 0f;
        }
        return delta;
    }

    public static class Builder {
        private final VoxeliteEngine coreEngine;
        private final VoxelientConfig.Builder configBuilder = VoxelientConfig.builder();

        private Builder(VoxeliteEngine coreEngine) {
            this.coreEngine = coreEngine;
        }

        public Builder textureAtlasPath(String path) {
            configBuilder.textureAtlasPath(path);
            return this;
        }

        public Builder textureProvider(kr.co.voxelite.world.BlockManager.IBlockTextureProvider provider) {
            configBuilder.textureProvider(provider);
            return this;
        }

        public Builder fieldOfView(float fov) {
            configBuilder.fieldOfView(fov);
            return this;
        }

        public Builder cameraPitch(float pitch) {
            configBuilder.cameraPitch(pitch);
            return this;
        }

        public Builder cameraFar(float far) {
            configBuilder.cameraFar(far);
            return this;
        }

        public Builder mouseSensitivity(float sensitivity) {
            configBuilder.mouseSensitivity(sensitivity);
            return this;
        }

        public Builder playerSpeed(float speed) {
            configBuilder.playerSpeed(speed);
            return this;
        }

        public Builder chunkMeshBuildPerFrame(int max) {
            configBuilder.chunkMeshBuildPerFrame(max);
            return this;
        }

        public Builder updateCoreEngine(boolean updateCoreEngine) {
            configBuilder.updateCoreEngine(updateCoreEngine);
            return this;
        }

        public VoxelientEngine build() {
            return new VoxelientEngine(coreEngine, configBuilder.build());
        }
    }
}
