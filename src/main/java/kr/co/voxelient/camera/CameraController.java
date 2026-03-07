package kr.co.voxelient.camera;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector3;
import kr.co.voxelient.input.InputHandler;
import kr.co.voxelite.entity.Player;
import kr.co.voxelite.physics.PhysicsSystem;

/**
 * Controls player movement and camera based on input.
 */
public class CameraController {
    protected final FPSCamera camera;
    protected final Player player;
    protected final PhysicsSystem physicsSystem;
    protected final InputHandler inputHandler;
    protected float moveSpeed = 5f;

    public CameraController(FPSCamera camera, Player player, PhysicsSystem physicsSystem, InputHandler inputHandler) {
        this.camera = camera;
        this.player = player;
        this.physicsSystem = physicsSystem;
        this.inputHandler = inputHandler;

        updateCameraPosition();
    }

    public void update(float delta) {
        if (inputHandler.isMouseLocked()) {
            int deltaX = inputHandler.getMouseDeltaX();
            int deltaY = inputHandler.getMouseDeltaY();
            float deltaYaw = deltaX * inputHandler.getMouseSensitivity();
            float deltaPitch = deltaY * inputHandler.getMouseSensitivity();
            camera.addYaw(deltaYaw);
            camera.addPitch(deltaPitch);
        }

        if (player.isGravityEnabled() && Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            physicsSystem.tryJump(player);
        }

        Vector3 direction = camera.getDirection();
        Vector3 moveDir = new Vector3();

        Vector3 horizontalDir = new Vector3(direction);
        horizontalDir.y = 0;
        horizontalDir.nor();

        if (Gdx.input.isKeyPressed(Input.Keys.W)) {
            moveDir.add(horizontalDir);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S)) {
            moveDir.sub(horizontalDir);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.A)) {
            Vector3 right = new Vector3();
            Vector3 up = new Vector3(0, 1, 0);
            right.set(horizontalDir).crs(up).nor();
            moveDir.sub(right);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D)) {
            Vector3 right = new Vector3();
            Vector3 up = new Vector3(0, 1, 0);
            right.set(horizontalDir).crs(up).nor();
            moveDir.add(right);
        }

        if (moveDir.len() > 0.001f) {
            moveDir.nor().scl(moveSpeed);
            player.getVelocity().x = moveDir.x;
            player.getVelocity().z = moveDir.z;
        } else {
            player.getVelocity().x = 0;
            player.getVelocity().z = 0;
        }

        physicsSystem.update(player, delta);

        updateCameraPosition();
        camera.update();
    }

    protected void updateCameraPosition() {
        camera.setPosition(player.getEyePosition());
    }

    public Player getPlayer() {
        return player;
    }

    public void setMoveSpeed(float moveSpeed) {
        this.moveSpeed = moveSpeed;
    }

    public float getMoveSpeed() {
        return moveSpeed;
    }
}
