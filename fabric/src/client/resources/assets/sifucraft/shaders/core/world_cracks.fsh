#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) discard;
    // Vanilla uses DST_COLOR + SRC_COLOR (2*texture*background). Its dark strokes
    // become black source-over ink; the light bevel becomes a translucent highlight.
    // Never send the transparent white texels as an opaque block-shaped overlay.
    float signedInk = 1.0 - 2.0 * dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float opacity = clamp(abs(signedInk) * color.a, 0.0, 1.0);
    if (opacity <= 1.0 / 255.0) discard;
    fragColor = vec4(vec3(signedInk < 0.0 ? opacity : 0.0), opacity);
}
