package org.nextrg.skylens.pipelines;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import earth.terrarium.olympus.client.utils.fabric.GuiGraphicsHelperImpl;
import kotlin.Pair;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.joml.Vector4f;
import org.nextrg.skylens.Skylens;
import org.nextrg.skylens.pipelines.pips.CircleChartPIPRenderer;
import org.nextrg.skylens.pipelines.uniforms.CircleChartUniform;

public class CircleChart {
    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Skylens.Companion.id("circle_chart"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("CircleChartUniform", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withFragmentShader(Skylens.Companion.id("core/circle_chart"))
            .withVertexShader(Skylens.Companion.id("core/basic_transform"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .build();
    
    /**
     * Draws a 2D pie chart that supports gradients on UI.
     * @param guiGraphics Context used to draw the ui element
     * @param x X position of <b>left</b> corner of the circle
     * @param y Y position of <b>top</b> corner of the circle
     * @param radius First value is the outer radius (size of the circle), and the second value is the inner radius (size of the hole inside the circle)
     * @param colors Array of colors each defined by <font color="afaded"><code>Vector4f</code></font> format, automatically retrieves the count from it and spreads them as a gradient on the circle
     * @param progress The fill amount of the circle starting from the angle defined with <code>startAngle</code>
     * @param time The value for the color shift in the gradient (automatically wraps from 0 to 1)
     * @param startAngle Angle of the circle that it starts from (by default at the top)
     * @param reverse When true, the color order is flipped
     * @param invert When true, the empty part is filled, while the filled part is empty
     *
     * @see CircleChartPIPRenderer
     * @see CircleChartUniform
     */
    public static void draw(GuiGraphics guiGraphics, float x, float y, Pair<Float, Float> radius, Vector4f[] colors, float progress, float time, float startAngle, boolean reverse, boolean invert) {
        CircleChartPIPRenderer.State state = new CircleChartPIPRenderer.State(
                guiGraphics,
                x + 2f, y + 2f,
                0xFFFFFFFF, colors, colors.length,
                radius.getFirst(), radius.getSecond(),
                progress, time, startAngle,
                reverse, invert
        );
        
        GuiGraphicsHelperImpl.submitPip(guiGraphics, state);
    }
}