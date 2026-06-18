package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.collision.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Sorts translucent chunks back-to-front so blending composes against already drawn pixels.
 */
final class TranslucentRenderOrder {
    private TranslucentRenderOrder() {
    }

    static <T> List<T> backToFront(Camera camera, List<T> items, Function<T, BoundingBox> boundsProvider) {
        if (camera == null || items == null || items.size() < 2) {
            return items;
        }

        List<DistanceItem<T>> ordered = new ArrayList<>(items.size());
        for (T item : items) {
            ordered.add(new DistanceItem<>(
                item,
                distanceSquaredToCenter(camera, boundsProvider.apply(item))
            ));
        }

        ordered.sort(Comparator.comparingDouble(DistanceItem<T>::distanceSquared).reversed());

        List<T> result = new ArrayList<>(ordered.size());
        for (DistanceItem<T> item : ordered) {
            result.add(item.value());
        }
        return result;
    }

    static float distanceSquaredToCenter(Camera camera, BoundingBox bounds) {
        if (camera == null || camera.position == null || bounds == null) {
            return 0f;
        }

        float centerX = (bounds.min.x + bounds.max.x) * 0.5f;
        float centerY = (bounds.min.y + bounds.max.y) * 0.5f;
        float centerZ = (bounds.min.z + bounds.max.z) * 0.5f;
        float dx = centerX - camera.position.x;
        float dy = centerY - camera.position.y;
        float dz = centerZ - camera.position.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private record DistanceItem<T>(T value, float distanceSquared) {
    }
}
