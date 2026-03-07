package kr.co.voxelient.render;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import kr.co.voxelite.util.PerformanceLogger;

import java.util.List;
import java.util.Locale;

/**
 * Renders chunk instances.
 */
public class BlockRenderer {
    private static final Color FOG_COLOR = new Color(0.87f, 0.95f, 1.0f, 1f);
    private static final float FOG_START_RATIO = 0.78f;
    private static final float FOG_END_RATIO = 0.94f;

    private final ModelBatch modelBatch;
    private final Environment environment;

    public BlockRenderer() {
        modelBatch = new ModelBatch(new DefaultShaderProvider(createShaderConfig()));
        environment = new Environment();
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 1f, 1f, 1f, 1f));
        environment.set(ColorAttribute.createFog(FOG_COLOR));
    }

    private DefaultShader.Config createShaderConfig() {
        DefaultShader.Config config = new DefaultShader.Config();
        config.vertexShader = createFogVertexShader();
        config.fragmentShader = DefaultShader.getDefaultFragmentShader();
        return config;
    }

    private String createFogVertexShader() {
        String defaultVertexShader = DefaultShader.getDefaultVertexShader();
        String fogBlock = ""
            + "    #ifdef fogFlag\n"
            + "        vec3 flen = u_cameraPosition.xyz - pos.xyz;\n"
            + "        float fog = dot(flen, flen) * u_cameraPosition.w;\n"
            + "        v_fog = min(fog, 1.0);\n"
            + "    #endif\n";
        String tunedFogBlock = String.format(
            Locale.US,
            "    #ifdef fogFlag%n"
                + "        vec3 flen = u_cameraPosition.xyz - pos.xyz;%n"
                + "        float fogDistance = sqrt(dot(flen, flen));%n"
                + "        float fogFar = sqrt(1.0 / max(u_cameraPosition.w, 0.000001));%n"
                + "        float fogStart = fogFar * %.2f;%n"
                + "        float fogEnd = fogFar * %.2f;%n"
                + "        float fogRange = max(fogEnd - fogStart, 0.0001);%n"
                + "        v_fog = clamp((fogDistance - fogStart) / fogRange, 0.0, 1.0);%n"
                + "    #endif%n",
            FOG_START_RATIO,
            FOG_END_RATIO
        );

        if (!defaultVertexShader.contains(fogBlock)) {
            return defaultVertexShader;
        }
        return defaultVertexShader.replace(fogBlock, tunedFogBlock);
    }

    public void render(PerspectiveCamera camera, ChunkMeshManager meshManager) {
        long t0 = PerformanceLogger.now();
        modelBatch.begin(camera);
        List<ModelInstance> instances = meshManager.getVisibleInstances(camera);
        long t1 = PerformanceLogger.now();
        for (ModelInstance instance : instances) {
            modelBatch.render(instance, environment);
        }
        modelBatch.end();
        long t2 = PerformanceLogger.now();
        if (PerformanceLogger.ENABLED && (t2 - t0 > 5 || instances.size() > 30)) {
            System.out.printf("[PERF][BlockRenderer] getInstances=%dms render=%dms drawCalls=%d%n",
                t1 - t0, t2 - t1, instances.size());
        }
    }

    public void dispose() {
        modelBatch.dispose();
    }
}
