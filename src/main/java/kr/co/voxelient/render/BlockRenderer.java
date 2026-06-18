package kr.co.voxelient.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import kr.co.voxelite.util.PerformanceLogger;
import kr.co.voxelite.world.BlockRenderLayer;

import java.util.List;
import java.util.Locale;

/**
 * Renders chunk instances.
 */
public class BlockRenderer {
    private static final Color FOG_COLOR = new Color(0.87f, 0.95f, 1.0f, 1f);

    private final ModelBatch modelBatch;
    private final Environment environment;
    private final float fogStartRatio;
    private final float fogEndRatio;

    public BlockRenderer() {
        this(0.78f, 0.94f);
    }

    public BlockRenderer(float fogStartRatio, float fogEndRatio) {
        this.fogStartRatio = clampFogRatio(fogStartRatio, 0.78f);
        this.fogEndRatio = clampFogEndRatio(this.fogStartRatio, fogEndRatio);
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
            fogStartRatio,
            fogEndRatio
        );

        if (!defaultVertexShader.contains(fogBlock)) {
            return defaultVertexShader;
        }
        return defaultVertexShader.replace(fogBlock, tunedFogBlock);
    }

    public void render(PerspectiveCamera camera, ChunkMeshManager meshManager) {
        long t0 = PerformanceLogger.now();
        int drawCalls = 0;

        beginOpaqueState();
        drawCalls += renderLayer(camera, meshManager, BlockRenderLayer.SOLID);
        drawCalls += renderLayer(camera, meshManager, BlockRenderLayer.CUTOUT);

        beginTranslucentState();
        drawCalls += renderLayer(camera, meshManager, BlockRenderLayer.TRANSLUCENT);
        endTranslucentState();

        long t2 = PerformanceLogger.now();
        if (PerformanceLogger.ENABLED && (t2 - t0 > 5 || drawCalls > 30)) {
            System.out.printf("[PERF][BlockRenderer] render=%dms drawCalls=%d%n",
                t2 - t0, drawCalls);
        }
    }

    private void beginOpaqueState() {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void beginTranslucentState() {
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);
    }

    private void endTranslucentState() {
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private int renderLayer(PerspectiveCamera camera, ChunkMeshManager meshManager, BlockRenderLayer renderLayer) {
        modelBatch.begin(camera);
        List<ModelInstance> instances = meshManager.getVisibleInstances(camera, renderLayer);
        for (ModelInstance instance : instances) {
            modelBatch.render(instance, environment);
        }
        modelBatch.end();
        return instances.size();
    }

    public void dispose() {
        modelBatch.dispose();
    }

    private float clampFogRatio(float value, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(0f, Math.min(0.99f, value));
    }

    private float clampFogEndRatio(float startRatio, float endRatio) {
        float safeEnd = Math.max(0f, Math.min(1f, Float.isFinite(endRatio) ? endRatio : 0.94f));
        return Math.max(startRatio + 0.01f, safeEnd);
    }
}
