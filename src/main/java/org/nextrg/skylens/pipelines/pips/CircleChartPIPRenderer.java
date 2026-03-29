package org.nextrg.skylens.pipelines.pips;

import com.mojang.blaze3d.vertex.VertexFormat;
import earth.terrarium.olympus.client.pipelines.pips.OlympusPictureInPictureRenderState;
import earth.terrarium.olympus.client.pipelines.renderer.PipelineRenderer;
import earth.terrarium.olympus.client.utils.GuiGraphicsHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.nextrg.skylens.pipelines.CircleChart;
import org.nextrg.skylens.pipelines.uniforms.CircleChartUniform;

import java.util.function.Function;

import static org.nextrg.skylens.helpers.VariablesUtil.getFloatCenter;

public class CircleChartPIPRenderer extends PictureInPictureRenderer<CircleChartPIPRenderer.State> {
    private State lastState;
    
    public CircleChartPIPRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }
    
    @Override
    public @NotNull Class<State> getRenderStateClass() {
        return State.class;
    }
    
    @Override
    protected boolean textureIsReadyToBlit(State state) {
        return lastState != null && lastState.equals(state);
    }
    
    @Override
    protected void renderToTexture(State state, PoseStack pose) {
        final float scale = Minecraft.getInstance().getWindow().getGuiScale();
        final float padding = 4f;

        final Vector2f size = new Vector2f(state.outerRadius * 2f * scale, state.outerRadius * 2f * scale);
        final float fSize = size.x + (padding * scale);
        final Vector2f center = getFloatCenter(state, new Vector2f(state.fx0, state.fx1), new Vector2f(state.fy0, state.fy1), scale);

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        
        buffer.addVertex(-4f, -4f, 0f).setColor(state.color());
        buffer.addVertex(-4f, fSize + 4f, 0f).setColor(state.color());
        buffer.addVertex(fSize + 4f, fSize + 4f, 0f).setColor(state.color());
        buffer.addVertex(fSize + 4f, -4f, 0f).setColor(state.color());

        PipelineRenderer.builder(CircleChart.PIPELINE, buffer.buildOrThrow())
                .uniform(
                        CircleChartUniform.STORAGE,
                        CircleChartUniform.of(
                                state.colors,
                                state.colorCount,
                                center,
                                state.outerRadius * scale,
                                state.innerRadius * scale,
                                state.progress,
                                state.time,
                                state.startAngle + (float)(Math.PI / 2),
                                state.reverse == 1,
                                state.invert == 1
                        )
                )
                .draw();
        this.lastState = state;
    }
    
    @Override
    protected @NotNull String getTextureLabel() {
        return "skylens_circle_chart";
    }
    
    public static record State(
            int x0, int y0,
            int x1, int y1,
            int color,
            Vector4f[] colors,
            int colorCount,
            float outerRadius,
            float innerRadius,
            float progress,
            float time,
            float startAngle,
            int reverse,
            int invert,
            float fx0,
            float fy0,
            float fx1,
            float fy1,
            Matrix3x2f pose,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds
    ) implements OlympusPictureInPictureRenderState<State> {
        public State(
                GuiGraphics graphics,
                float x, float y,
                int color,
                Vector4f[] colors,
                int colorCount,
                float outerRadius,
                float innerRadius,
                float progress,
                float time,
                float startAngle,
                boolean reverse,
                boolean invert
        ) {
            this(
                    (int)Math.floor(x - outerRadius - 2.0),
                    (int)Math.floor(y - outerRadius - 2.0),
                    (int)Math.ceil(x + outerRadius + 2.0),
                    (int)Math.ceil(y + outerRadius + 2.0),
                    color,
                    colors,
                    colorCount,
                    outerRadius,
                    innerRadius,
                    progress,
                    time,
                    startAngle,
                    reverse ? 1 : 0,
                    invert ? 1 : 0,
                    x - outerRadius - 2f,
                    y - outerRadius - 2f,
                    x + outerRadius + 2f,
                    y + outerRadius + 2f,
                    new Matrix3x2f(graphics.pose()),
                    GuiGraphicsHelper.getLastScissor(graphics),
                    PictureInPictureRenderState.getBounds(
                            (int)Math.floor(x - outerRadius - 2.0),
                            (int)Math.floor(y - outerRadius - 2.0),
                            (int)Math.ceil(x + outerRadius + 2.0),
                            (int)Math.ceil(y + outerRadius + 2.0),
                            GuiGraphicsHelper.getLastScissor(graphics)
                    )
            );
        }
        
        @Override
        public int x0() { return x0; }
        
        @Override
        public int y0() { return y0; }
        
        @Override
        public int x1() { return x1; }
        
        @Override
        public int y1() { return y1; }
        
        @Override
        public float scale() { return 1.0F; }
        
        @Override
        public Function<MultiBufferSource.BufferSource, PictureInPictureRenderer<State>> getFactory() {
            return CircleChartPIPRenderer::new;
        }
        
        public int color() {
            return this.color;
        }
    }
}