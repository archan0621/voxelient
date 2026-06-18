package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.collision.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslucentRenderOrderTest {

    @Test
    void backToFront_ShouldSortFarthestBoundsFirst() {
        PerspectiveCamera camera = new PerspectiveCamera();
        camera.position.set(0f, 0f, 0f);

        OrderedBounds near = new OrderedBounds("near", boundsAround(0f, 0f, -4f));
        OrderedBounds far = new OrderedBounds("far", boundsAround(0f, 0f, -20f));
        OrderedBounds middle = new OrderedBounds("middle", boundsAround(0f, 0f, -10f));

        List<OrderedBounds> sorted = TranslucentRenderOrder.backToFront(
            camera,
            List.of(near, far, middle),
            OrderedBounds::bounds
        );

        assertEquals(List.of(far, middle, near), sorted);
    }

    @Test
    void distanceSquaredToCenter_ShouldUseBoundingBoxCenter() {
        PerspectiveCamera camera = new PerspectiveCamera();
        camera.position.set(1f, 2f, 3f);

        BoundingBox bounds = new BoundingBox();
        bounds.set(
            new com.badlogic.gdx.math.Vector3(4f, 6f, 8f),
            new com.badlogic.gdx.math.Vector3(6f, 8f, 10f)
        );

        assertEquals(77f, TranslucentRenderOrder.distanceSquaredToCenter(camera, bounds));
    }

    private BoundingBox boundsAround(float x, float y, float z) {
        BoundingBox bounds = new BoundingBox();
        bounds.set(
            new com.badlogic.gdx.math.Vector3(x - 1f, y - 1f, z - 1f),
            new com.badlogic.gdx.math.Vector3(x + 1f, y + 1f, z + 1f)
        );
        return bounds;
    }

    private record OrderedBounds(String name, BoundingBox bounds) {
    }
}
