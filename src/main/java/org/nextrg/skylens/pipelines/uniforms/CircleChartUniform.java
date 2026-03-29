package org.nextrg.skylens.pipelines.uniforms;

import com.google.common.base.Suppliers;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import earth.terrarium.olympus.client.pipelines.uniforms.RenderPipelineUniforms;
import net.minecraft.client.renderer.DynamicUniformStorage;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

public record CircleChartUniform(
        Vector4f[] colors,
        int colorCount,
        Vector2f center,
        float outerRadius,
        float innerRadius,
        float progress,
        float time,
        float startAngle,
        int reverse,
        int invert
) implements RenderPipelineUniforms {

    public static final String NAME = "CircleChartUniform";
    
    public static final Supplier<DynamicUniformStorage<CircleChartUniform>> STORAGE =
            Suppliers.<DynamicUniformStorage<CircleChartUniform>>memoize(() -> new DynamicUniformStorage<>(
                    "Circle Chart UBO",
                    new Std140SizeCalculator()
                            .putVec4() // color 0
                            .putVec4() // color 1
                            .putVec4() // color 2
                            .putVec4() // color 3
                            .putVec4() // color 4
                            .putVec4() // color 5
                            .putVec4() // color 6
                            .putVec4() // color 7
                            .putInt()
                            .putVec2()
                            .putFloat()
                            .putFloat()
                            .putFloat()
                            .putFloat()
                            .putFloat()
                            .putInt()
                            .putInt()
                            .get(),
                    2
            ));
    
    public static CircleChartUniform of(
            Vector4f[] colors,
            int colorCount,
            Vector2f center,
            float outerRadius,
            float innerRadius,
            float progress,
            float time,
            float startAngle,
            boolean reverse,
            boolean invert
    ) {
        return new CircleChartUniform(
                colors,
                colorCount,
                center,
                outerRadius,
                innerRadius,
                progress,
                time,
                startAngle,
                reverse ? 1 : 0,
                invert ? 1 : 0
        );
    }
    
    @Override
    public String name() {
        return NAME;
    }
    
    @Override
    public void write(ByteBuffer buffer) {
        Std140Builder builder = Std140Builder.intoBuffer(buffer);
        for (int i = 0; i < 8; i++) {
            builder.putVec4(i < colors.length ? colors[i] : new Vector4f(0,0,0,0));
        }
        builder.putInt(colorCount)
                .putVec2(center)
                .putFloat(outerRadius)
                .putFloat(innerRadius)
                .putFloat(progress)
                .putFloat(time)
                .putFloat(startAngle)
                .putInt(reverse)
                .putInt(invert)
                .get();
    }
}