package dev.bngmc.bridge.client;

import java.lang.reflect.Method;

/**
 * Iris (shader packs), when it is installed: reached by reflection through its public API
 * (net.irisshaders.iris.api.v0.IrisApi), so the mod neither needs nor ships it. A shader pack
 * renders the world through its own buffers and passes, and the car drawn with our own shader
 * among the entities was painted over (seen: invisible under Complementary Reimagined), so with
 * a pack on, NativeCars draws after the pack has finished the frame.
 */
final class ShaderCompat {
	private ShaderCompat() {
	}

	private static final Object API;
	private static final Method IN_USE;
	private static final Method SHADOW_PASS;

	static {
		Object api = null;
		Method inUse = null, shadow = null;
		try {
			Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			api = c.getMethod("getInstance").invoke(null);
			inUse = c.getMethod("isShaderPackInUse");
			shadow = c.getMethod("isRenderingShadowPass");
		} catch (ReflectiveOperationException | LinkageError e) {
			api = null;   // no Iris: Minecraft's own renderer
		}
		API = api;
		IN_USE = inUse;
		SHADOW_PASS = shadow;
	}

	/** A shader pack is rendering the world. */
	static boolean packInUse() {
		return call(IN_USE);
	}

	/** Iris is drawing the shadow map (entities included), not the picture. */
	static boolean shadowPass() {
		return call(SHADOW_PASS);
	}

	private static boolean call(Method m) {
		if (API == null || m == null) {
			return false;
		}
		try {
			return (boolean) m.invoke(API);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return false;
		}
	}
}
