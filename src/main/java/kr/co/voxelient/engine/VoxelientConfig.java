package kr.co.voxelient.engine;

import kr.co.voxelite.world.BlockManager;

/**
 * Configuration for VoxelientEngine.
 */
public class VoxelientConfig {
    public String textureAtlasPath = null;
    public BlockManager.IBlockTextureProvider textureProvider = null;
    public float fieldOfView = 67f;
    public float initialPitch = -20f;
    public float cameraFar = 160f;
    public float mouseSensitivity = 0.1f;
    public float playerMoveSpeed = 5f;
    public int chunkMeshBuildPerFrame = 2;
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

        public Builder fieldOfView(float fov) {
            config.fieldOfView = fov;
            return this;
        }

        public Builder cameraPitch(float pitch) {
            config.initialPitch = pitch;
            return this;
        }

        public Builder cameraFar(float far) {
            config.cameraFar = far;
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

        public Builder updateCoreEngine(boolean updateCoreEngine) {
            config.updateCoreEngine = updateCoreEngine;
            return this;
        }

        public VoxelientConfig build() {
            return config;
        }
    }
}
