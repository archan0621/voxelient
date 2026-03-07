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

    public Renderer(int screenWidth, int screenHeight) {
        blockRenderer = new BlockRenderer();
        crosshairRenderer = new CrosshairRenderer(screenWidth, screenHeight);
        blockOutlineRenderer = new BlockOutlineRenderer();
        hudRenderer = new HudRenderer();
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
        hudRenderer.render(playerPos, logicalWidth, logicalHeight);
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
