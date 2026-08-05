package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
final class MetalPipelineSupport {
    private MetalPipelineSupport() {
    }

    static boolean sameHandle(@Nullable final MemorySegment left, @Nullable final MemorySegment right) {
        long leftValue = left == null ? 0L : left.address();
        long rightValue = right == null ? 0L : right.address();
        return leftValue == rightValue;
    }

    static List<String> vertexAttributeNames(final RenderPipeline pipeline) {
        List<String> names = new ArrayList<>();
        // 1.21.11 单 VertexFormat（26.2 的 getVertexFormatBindings 为多绑定列表）
        VertexFormat binding = pipeline.getVertexFormat();
        for (VertexFormatElement element : binding.getElements()) {
            names.add(binding.getElementName(element));
        }
        return names;
    }
}
