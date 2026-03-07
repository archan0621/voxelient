package kr.co.voxelient.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Vector3;
import kr.co.voxelite.world.Chunk;
import kr.co.voxelite.world.ChunkCoord;

/**
 * HUD Renderer - displays player information.
 */
public class HudRenderer {
    private final SpriteBatch batch;
    private final BitmapFont font;

    public HudRenderer() {
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.setColor(Color.WHITE);
        font.getData().setScale(1.5f);
    }

    public void render(Vector3 playerPos, int screenWidth, int screenHeight) {
        ChunkCoord chunkCoord = ChunkCoord.fromWorldPos(playerPos.x, playerPos.z, Chunk.CHUNK_SIZE);
        int blockX = (int) Math.floor(playerPos.x);
        int blockY = (int) Math.floor(playerPos.y);
        int blockZ = (int) Math.floor(playerPos.z);

        batch.begin();
        float x = 10f;
        float y = screenHeight - 20f;
        font.draw(batch, String.format("Position: (%d, %d, %d)", blockX, blockY, blockZ), x, y);
        y -= 25f;
        font.draw(batch, String.format("Chunk: (%d, %d)", chunkCoord.x, chunkCoord.z), x, y);
        y -= 25f;
        font.draw(batch, String.format("FPS: %d", Gdx.graphics.getFramesPerSecond()), x, y);
        batch.end();
    }

    public void dispose() {
        batch.dispose();
        font.dispose();
    }
}
