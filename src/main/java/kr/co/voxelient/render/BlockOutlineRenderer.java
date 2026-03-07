package kr.co.voxelient.render;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

/**
 * Renders block selection outline.
 */
public class BlockOutlineRenderer {
    private Mesh lineMesh;
    private ShaderProgram shader;
    private Matrix4 transform;

    private static final float EPSILON = 0.01f;
    private static final float HALF_SIZE = 0.5f;
    private static final float MIN = -HALF_SIZE - EPSILON;
    private static final float MAX = HALF_SIZE + EPSILON;

    public BlockOutlineRenderer() {
        createLineMesh();
        createShader();
        transform = new Matrix4();
    }

    private void createLineMesh() {
        float[][] corners = {
            {MIN, MIN, MIN}, {MAX, MIN, MIN}, {MAX, MIN, MAX}, {MIN, MIN, MAX},
            {MIN, MAX, MIN}, {MAX, MAX, MIN}, {MAX, MAX, MAX}, {MIN, MAX, MAX}
        };
        int[][] edges = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        float[] vertices = new float[24 * 7];
        int idx = 0;

        for (int[] edge : edges) {
            float[] start = corners[edge[0]];
            vertices[idx++] = start[0];
            vertices[idx++] = start[1];
            vertices[idx++] = start[2];
            vertices[idx++] = 0f;
            vertices[idx++] = 0f;
            vertices[idx++] = 0f;
            vertices[idx++] = 1f;

            float[] end = corners[edge[1]];
            vertices[idx++] = end[0];
            vertices[idx++] = end[1];
            vertices[idx++] = end[2];
            vertices[idx++] = 0f;
            vertices[idx++] = 0f;
            vertices[idx++] = 0f;
            vertices[idx++] = 1f;
        }

        lineMesh = new Mesh(
            true,
            24,
            0,
            new VertexAttribute(Usage.Position, 3, ShaderProgram.POSITION_ATTRIBUTE),
            new VertexAttribute(Usage.ColorUnpacked, 4, ShaderProgram.COLOR_ATTRIBUTE)
        );
        lineMesh.setVertices(vertices);
    }

    private void createShader() {
        String vertexShader =
            "attribute vec3 " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" +
            "attribute vec4 " + ShaderProgram.COLOR_ATTRIBUTE + ";\n" +
            "uniform mat4 u_projViewTrans;\n" +
            "uniform mat4 u_worldTrans;\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "    v_color = " + ShaderProgram.COLOR_ATTRIBUTE + ";\n" +
            "    gl_Position = u_projViewTrans * u_worldTrans * vec4(" + ShaderProgram.POSITION_ATTRIBUTE + ", 1.0);\n" +
            "}\n";

        String fragmentShader =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "    gl_FragColor = v_color;\n" +
            "}\n";

        shader = new ShaderProgram(vertexShader, fragmentShader);
        if (!shader.isCompiled()) {
            throw new RuntimeException("Shader compilation failed: " + shader.getLog());
        }
    }

    public void render(Camera camera, Vector3 blockPos) {
        if (blockPos == null) {
            return;
        }

        transform.idt().setToTranslation(blockPos);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glLineWidth(2.0f);

        shader.bind();
        shader.setUniformMatrix("u_projViewTrans", camera.combined);
        shader.setUniformMatrix("u_worldTrans", transform);
        lineMesh.render(shader, GL20.GL_LINES);

        Gdx.gl.glDepthMask(true);
        Gdx.gl.glLineWidth(1.0f);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    public void dispose() {
        if (lineMesh != null) {
            lineMesh.dispose();
        }
        if (shader != null) {
            shader.dispose();
        }
    }
}
