package kr.co.voxelient.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import kr.co.voxelient.camera.FPSCamera;

/**
 * Main renderer coordinating all rendering components.
 */
public class Renderer {
    private final BlockRenderer blockRenderer;
    private final CrosshairRenderer crosshairRenderer;
    private final BlockOutlineRenderer blockOutlineRenderer;
    private final HudRenderer hudRenderer;
    private final boolean showRenderStats;

    public Renderer(int screenWidth, int screenHeight) {
        this(screenWidth, screenHeight, 0.78f, 0.94f);
    }

    public Renderer(int screenWidth, int screenHeight, float fogStartRatio, float fogEndRatio) {
        this(screenWidth, screenHeight, fogStartRatio, fogEndRatio, false);
    }

    public Renderer(int screenWidth, int screenHeight, float fogStartRatio, float fogEndRatio, boolean showRenderStats) {
        blockRenderer = new BlockRenderer(fogStartRatio, fogEndRatio);
        crosshairRenderer = new CrosshairRenderer(screenWidth, screenHeight);
        blockOutlineRenderer = new BlockOutlineRenderer();
        hudRenderer = new HudRenderer();
        this.showRenderStats = showRenderStats;
    }

    public void render(
        FPSCamera fpsCamera,
        ChunkMeshManager chunkMeshManager,
        int logicalWidth,
        int logicalHeight,
        Vector3 selectedBlock,
        Vector3 playerPos
    ) {
        int backBufferWidth = Gdx.graphics.getBackBufferWidth();
        int backBufferHeight = Gdx.graphics.getBackBufferHeight();
        Gdx.gl.glViewport(0, 0, backBufferWidth, backBufferHeight);
        Gdx.gl.glClearColor(0.87f, 0.95f, 1.0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        fpsCamera.resize(logicalWidth, logicalHeight);
        blockRenderer.render(fpsCamera.getCamera(), chunkMeshManager);

        if (selectedBlock != null) {
            blockOutlineRenderer.render(fpsCamera.getCamera(), selectedBlock);
        }

        crosshairRenderer.render(logicalWidth, logicalHeight);
        ChunkMeshStats stats = showRenderStats && chunkMeshManager != null
            ? chunkMeshManager.getStats()
            : null;
        hudRenderer.render(playerPos, logicalWidth, logicalHeight, stats);
    }

    public void resize(int width, int height) {
        crosshairRenderer.resize(width, height);
    }

    public void dispose() {
        blockRenderer.dispose();
        crosshairRenderer.dispose();
        blockOutlineRenderer.dispose();
        hudRenderer.dispose();
    }
}
