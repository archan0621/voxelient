package kr.co.voxelient.input;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

/**
 * Manages input handling (mouse and keyboard).
 */
public class InputHandler {
    private final MouseHandler mouseHandler;

    public InputHandler() {
        mouseHandler = new MouseHandler();
    }

    public void update(float delta) {
        mouseHandler.update();

        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            mouseHandler.unlockMouse();
        }

        mouseHandler.handleMouseClick();
    }

    public MouseHandler getMouseHandler() {
        return mouseHandler;
    }

    public boolean isMouseLocked() {
        return mouseHandler.isMouseLocked();
    }

    public int getMouseDeltaX() {
        return mouseHandler.getMouseDeltaX();
    }

    public int getMouseDeltaY() {
        return mouseHandler.getMouseDeltaY();
    }

    public float getMouseSensitivity() {
        return mouseHandler.getMouseSensitivity();
    }
}
