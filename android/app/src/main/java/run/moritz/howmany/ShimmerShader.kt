package run.moritz.howmany

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// Pixels shift by up to this much, in noise blobs about this wide.
private val AMPLITUDE = 2.dp
private val BLOB = 80.dp
// Contours are found between pixels this far apart.
private val CONTOUR_STEP = 1.dp

/**
 * Lets the photo shimmer while it is analysed: it wobbles slightly along slowly drifting noise,
 * except for the exemplar, its contours glow faintly, and brightly where a soft ring of light
 * spreads from the exemplar across the crop.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class ShimmerShader {
    private val shader = RuntimeShader(SHADER)

    /**
     * The render effect in view coordinates at [seconds] into the scan: the ring of light spreads
     * from [exemplar] to [reach] away from it every [period] seconds, contours glow from [contours]
     * on, and everything shows by [fade], from 0 for not at all to 1 for fully.
     */
    fun effect(
        exemplar: Rect,
        crop: Rect,
        reach: Float,
        seconds: Float,
        period: Float,
        fade: Float,
        contours: ContourThresholds,
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
        shader.setFloatUniform("reach", reach)
        shader.setFloatUniform("time", seconds)
        shader.setFloatUniform("period", period)
        shader.setFloatUniform("fade", fade)
        shader.setFloatUniform("amplitude", with(density) { AMPLITUDE.toPx() })
        shader.setFloatUniform("blob", with(density) { BLOB.toPx() })
        shader.setFloatUniform("spacing", with(density) { CONTOUR_STEP.toPx() })
        shader.setFloatUniform("contours", contours.from, contours.to)
        return AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }

    private companion object {
        const val SHADER =
            """
            uniform shader content;
            uniform float4 exemplar;
            uniform float4 crop;
            uniform float reach;
            uniform float time;
            uniform float period;
            uniform float fade;
            uniform float amplitude;
            uniform float blob;
            uniform float spacing;
            // Brightness changes around a pixel from which on it counts as a contour, and fully.
            uniform float2 contours;

            // How fast the noise drifts, in blobs per second.
            const float DRIFT = 0.3;
            // Where the second noise, for the other axis, is sampled apart from the first, in blobs.
            const float NOISE_APART = 17.0;
            // The ring of light starts this far inside the exemplar and travels this far per
            // period, in reaches, so it clears the farthest corner before the next one sets off.
            const float RING_START = 0.5;
            const float RING_TRAVEL = 2.0;
            // How sharply the ring of light falls off on either side.
            const float RING_SHARPNESS = 25.0;
            // The contours glow in this cool white, faintly everywhere and brightly in the ring.
            const half3 GLOW_COLOR = half3(0.85, 0.92, 1.0);
            const float CONTOUR_GLOW = 0.12;
            const float RING_CONTOUR_GLOW = 0.6;
            const float GLOW_ALPHA = 0.455;
            // How much the ring brightens everything it passes.
            const float RING_BRIGHTNESS = 0.026;

            // How far p lies outside the exemplar, from its nearest edge; zero inside.
            float distanceToExemplar(float2 p) {
                return length(max(max(exemplar.xy - p, p - exemplar.zw), float2(0)));
            }

            float hash(float2 p) {
                return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
            }

            // Smooth value noise between 0 and 1.
            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + float2(1, 0)), u.x),
                           mix(hash(i + float2(0, 1)), hash(i + float2(1, 1)), u.x), u.y);
            }

            float luma(float2 p) {
                return dot(content.eval(p).rgb, half3(0.299, 0.587, 0.114));
            }

            half4 main(float2 p) {
                if (p.x < crop.x || p.y < crop.y || p.x > crop.z || p.y > crop.w) {
                    return content.eval(p);
                }
                // Wobble, calming towards the exemplar so the exemplar stays still without a seam.
                float fromExemplar = distanceToExemplar(p);
                float calm = smoothstep(0.0, blob * 0.5, fromExemplar);
                float2 q = p / blob;
                float2 n = float2(noise(q + time * DRIFT), noise(q + NOISE_APART - time * DRIFT)) - 0.5;
                // Noise from -0.5 to 0.5, so twice the amplitude.
                float2 s = p + n * 2.0 * amplitude * calm * fade;
                half4 color = content.eval(s);

                // Contours: how sharply the brightness changes around s.
                float gx = luma(s + float2(spacing, 0)) - luma(s - float2(spacing, 0));
                float gy = luma(s + float2(0, spacing)) - luma(s - float2(0, spacing));
                float contour = smoothstep(contours.x, contours.y, length(float2(gx, gy)));

                // The ring spreads from inside the exemplar to past the farthest corner.
                float d = fromExemplar / reach - (fract(time / period) * RING_TRAVEL - RING_START);
                float ring = exp(-d * d * RING_SHARPNESS);

                color.rgb += GLOW_COLOR * contour * (CONTOUR_GLOW + ring * RING_CONTOUR_GLOW) * GLOW_ALPHA * fade * color.a;
                color.rgb += half3(ring * RING_BRIGHTNESS * fade) * color.a;
                return color;
            }
            """
    }
}
