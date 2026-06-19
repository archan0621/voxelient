package kr.co.voxelient.engine;

import kr.co.voxelite.world.BlockManager;

/**
 * Configuration for VoxelientEngine.
 */
public class VoxelientConfig {
    public String textureAtlasPath = null;
    public BlockManager.IBlockTextureProvider textureProvider = null;
    public BlockManager.IBlockRenderLayerProvider renderLayerProvider = null;
    public float fieldOfView = 67f;
    public float initialPitch = -20f;
    public float cameraNear = 0.05f;
    public float cameraFar = 160f;
    public float fogStartRatio = 0.78f;
    public float fogEndRatio = 0.94f;
    public float mouseSensitivity = 0.1f;
    public float playerMoveSpeed = 5f;
    public int chunkMeshBuildPerFrame = 2;
    public int chunkMeshApplyPerFrame = 8;
    public long chunkMeshApplyBudgetMs = 4L;
    public boolean showRenderStats = false;
    public boolean updateCoreEngine = true;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final VoxelientConfig config = new VoxelientConfig();

        public Builder textureAtlasPath(String path) {
            config.textureAtlasPath = path;
            return this;
        }

        public Builder textureProvider(BlockManager.IBlockTextureProvider provider) {
            config.textureProvider = provider;
            return this;
        }

        public Builder renderLayerProvider(BlockManager.IBlockRenderLayerProvider provider) {
            config.renderLayerProvider = provider;
            return this;
        }

        public Builder fieldOfView(float fov) {
            config.fieldOfView = fov;
            return this;
        }

        public Builder cameraPitch(float pitch) {
            config.initialPitch = pitch;
            return this;
        }

        public Builder cameraNear(float near) {
            config.cameraNear = near;
            return this;
        }

        public Builder cameraFar(float far) {
            config.cameraFar = far;
            return this;
        }

        public Builder fogRange(float startRatio, float endRatio) {
            config.fogStartRatio = startRatio;
            config.fogEndRatio = endRatio;
            return this;
        }

        public Builder mouseSensitivity(float sensitivity) {
            config.mouseSensitivity = sensitivity;
            return this;
        }

        public Builder playerSpeed(float speed) {
            config.playerMoveSpeed = speed;
            return this;
        }

        public Builder chunkMeshBuildPerFrame(int max) {
            config.chunkMeshBuildPerFrame = max;
            return this;
        }

        public Builder chunkMeshApplyPerFrame(int max) {
            config.chunkMeshApplyPerFrame = max;
            return this;
        }

        public Builder chunkMeshApplyBudgetMs(long maxMillis) {
            config.chunkMeshApplyBudgetMs = maxMillis;
            return this;
        }

        public Builder showRenderStats(boolean showRenderStats) {
            config.showRenderStats = showRenderStats;
            return this;
        }

        public Builder updateCoreEngine(boolean updateCoreEngine) {
            config.updateCoreEngine = updateCoreEngine;
            return this;
        }

        public VoxelientConfig build() {
            return config;
        }
    }
}
