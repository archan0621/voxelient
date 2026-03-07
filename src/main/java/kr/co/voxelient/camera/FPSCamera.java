package kr.co.voxelient.camera;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * First-person camera with yaw/pitch control.
 */
public class FPSCamera {
    private final PerspectiveCamera camera;
    private float yaw;
    private float pitch;

    public FPSCamera(float fieldOfView, int width, int height) {
        camera = new PerspectiveCamera(fieldOfView, width, height);
        camera.near = 0.1f;
        camera.far = 100f;
        yaw = -90f;
        pitch = 0f;
    }

    public void setPosition(float x, float y, float z) {
        camera.position.set(x, y, z);
    }

    public void setPosition(Vector3 position) {
        camera.position.set(position);
    }

    public Vector3 getPosition() {
        return camera.position;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setPitch(float pitch) {
        this.pitch = MathUtils.clamp(pitch, -89f, 89f);
    }

    public void addYaw(float delta) {
        yaw += delta;
    }

    public void addPitch(float delta) {
        pitch = MathUtils.clamp(pitch + delta, -89f, 89f);
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public Vector3 getDirection() {
        Vector3 direction = new Vector3();
        direction.x = MathUtils.cosDeg(yaw) * MathUtils.cosDeg(pitch);
        direction.y = MathUtils.sinDeg(pitch);
        direction.z = MathUtils.sinDeg(yaw) * MathUtils.cosDeg(pitch);
        direction.nor();
        return direction;
    }

    public void update() {
        Vector3 direction = getDirection();
        Vector3 target = new Vector3(camera.position).add(direction);
        camera.lookAt(target);
        camera.up.set(0, 1, 0);
        camera.update();
    }

    public void resize(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
    }

    public void setFar(float far) {
        camera.far = far;
        camera.update();
    }

    public PerspectiveCamera getCamera() {
        return camera;
    }
}
