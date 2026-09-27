package run.moritz.quantify

import android.graphics.BitmapShader
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// The front bends the photo like water: pixels shift by up to about this much where it passes,
// easing in and out over about this width on either side so it has no visible edges.
private val AMPLITUDE = 3.dp
private val WIDTH = 96.dp
// How brightly the front shines.
private const val BRIGHTNESS = 0.06f

/**
 * Bends the photo along the reveal's front like light through water: pixels move towards or away
 * from the exemplar where the front passes, colors split slightly, and the front shines. The
 * exemplar itself stays still.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class DistortionShader {
    private val shader = RuntimeShader(SHADER)

    /**
     * The render effect in view coordinates for a front of [radius] and [strength], in [arrival]
     * times [reach], the distance the wave covers from the edge of [exemplar] to the farthest
     * corner of [crop].
     */
    fun effect(
        exemplar: Rect,
        crop: Rect,
        radius: Float,
        strength: Float,
        arrival: BitmapShader,
        reach: Float,
        density: Density,
    ): RenderEffect {
        shader.setFloatUniform(
            "exemplar",
            exemplar.left,
            exemplar.top,
            exemplar.right,
            exemplar.bottom,
        )
        shader.setFloatUniform("crop", crop.left, crop.top, crop.right, crop.bottom)
        shader.setFloatUniform("ring", radius, strength)
        shader.setFloatUniform("reach", reach)
        shader.setFloatUniform("amplitude", with(density) { AMPLITUDE.toPx() })
        shader.setFloatUniform("width", with(density) { WIDTH.toPx() })
        shader.setFloatUniform("brightness", BRIGHTNESS)
        shader.setInputShader("arrival", arrival)
        return AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }

    private companion object {
        const val SHADER =
            """
            uniform shader content;
            uniform shader arrival;
            uniform float4 exemplar;
            uniform float4 crop;
            uniform float2 ring;
            uniform float reach;
            uniform float amplitude;
            uniform float width;
            uniform float brightness;

            // Pixels apart to sample the field on either side, finding which way the wave runs.
            const float GRADIENT_STEP = 2.0;
            // Scales a crest's peak of about 0.43 to about 1, so pixels shift by about amplitude.
            const float CREST_TO_AMPLITUDE = 2.3;
            // How much further red and less far blue shift than green, splitting colors slightly.
            const float RED_SHIFT = 1.1;
            const float BLUE_SHIFT = 0.9;

            // How far p lies outside the exemplar, from its nearest edge; zero inside.
            float distanceToExemplar(float2 p) {
                return length(max(max(exemplar.xy - p, p - exemplar.zw), float2(0)));
            }

            // How far the wave has come to p, in pixels.
            float field(float2 p) {
                return arrival.eval(p).r * reach;
            }

            // One wave crest: pushes outwards just ahead of the ring, inwards just behind it.
            float crest(float d) {
                float x = d / width;
                return x * exp(-x * x);
            }

            half4 main(float2 p) {
                if (p.x < crop.x || p.y < crop.y || p.x > crop.z || p.y > crop.w) {
                    return content.eval(p);
                }
                float d = field(p);
                float e = GRADIENT_STEP;
                float2 direction = float2(field(p + float2(e, 0)) - field(p - float2(e, 0)),
                                          field(p + float2(0, e)) - field(p - float2(0, e)));
                float size = length(direction);
                direction = size > 0.0001 ? direction / size : float2(0);
                float push = ring.y * crest(d - ring.x);
                // Fades in from the exemplar's edge, so the exemplar stays still without a seam.
                push *= smoothstep(0.0, width * 0.5, distanceToExemplar(p));
                float2 offset = direction * push * amplitude * CREST_TO_AMPLITUDE;
                half4 color = content.eval(p - offset);
                color.r = content.eval(p - offset * RED_SHIFT).r;
                color.b = content.eval(p - offset * BLUE_SHIFT).b;
                float behind = (d - ring.x) / width;
                float shine = ring.y * exp(-behind * behind) * brightness;
                color.rgb += half3(shine) * color.a;
                return color;
            }
            """
    }
}
