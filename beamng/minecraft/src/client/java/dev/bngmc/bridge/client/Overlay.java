package dev.bngmc.bridge.client;

import dev.bngmc.bridge.BngWorld;
import dev.bngmc.bridge.link.BngLink;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overlay mode (adapted from upstream's macOS Overlay): Minecraft's window becomes a borderless,
 * always-on-top, transparent layer glued to BeamNG's client area. Only Minecraft's own content
 * (blocks, entities, hand, HUD) is opaque; BeamNG shows through everywhere else, composited by
 * Windows' DWM. No occlusion: Minecraft is simply drawn on top (that's the compositor's job later).
 *
 * <p>The window is always created with a transparent framebuffer so the overlay can switch on and
 * off at runtime; while off, the final blit forces alpha to 1 and the window looks normal.
 * F6 toggles it; -Dbngbridge.noOverlay=true disables it; -Dbngbridge.noTransparency=true doesn't
 * even request the transparent framebuffer.
 */
public final class Overlay {
	private Overlay() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static final long FIND_INTERVAL_MS = 1000;

	private static boolean transparentWindow;
	private static boolean enabled = !Boolean.getBoolean("bngbridge.noOverlay");
	private static boolean active;
	private static long window;
	private static long bngHwnd;
	private static long lastFindMs;
	private static long lastWantMs;
	private static int savedX, savedY, savedW, savedH;
	private static Win32.Rect applied;

	public static boolean active() {
		return active;
	}

	/**
	 * Overlay mode where Minecraft's own world is see-through (BeamNG hosts): no sky, clouds,
	 * weather or fog. In a Minecraft-hosted world the overlay is active but the world stays opaque
	 * and only car cutouts are see-through (CarCutouts).
	 */
	public static boolean seeThroughWorld() {
		return active && !BngWorld.isHostWorld();
	}

	public static boolean transparentWindow() {
		return transparentWindow;
	}

	public static boolean enabled() {
		return enabled;
	}

	public static boolean toggle() {
		enabled = !enabled;
		return enabled;
	}

	public static long window() {
		return window;
	}

	/** BeamNG's main window handle (0 if not found yet). */
	public static long beamngHwnd() {
		return bngHwnd;
	}

	public static Win32.Rect appliedRect() {
		return applied;
	}

	/** Right before GLFW creates Minecraft's window (WindowMixin). */
	public static void applyWindowHints() {
		if (!Win32.AVAILABLE || Boolean.getBoolean("bngbridge.noTransparency")) {
			return;
		}
		GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
		transparentWindow = true;
	}

	public static void onWindowCreated(long handle) {
		window = handle;
		if (handle != 0L && transparentWindow) {
			transparentWindow = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_TRANSPARENT_FRAMEBUFFER) == GLFW.GLFW_TRUE;
			LOG.info("Minecraft window created, transparent framebuffer {}", transparentWindow ? "available" : "NOT available");
		}
	}

	/** Once per frame, render thread, before anything is drawn (MinecraftMixin). */
	public static void onFrame(Minecraft mc) {
		if (window == 0L || !Win32.AVAILABLE) {
			return;
		}
		long now = System.currentTimeMillis();
		if ((bngHwnd == 0 || Win32.clientRect(bngHwnd) == null) && now - lastFindMs > FIND_INTERVAL_MS) {
			lastFindMs = now;
			long h = Win32.findBeamngWindow();
			if (h != bngHwnd) {
				bngHwnd = h;
				if (h != 0) {
					LOG.info("Found BeamNG's window ({})", Long.toHexString(h));
				}
			}
		}
		Win32.Rect rect = bngHwnd != 0 ? Win32.clientRect(bngHwnd) : null;
		// A minimised window reports 0x0 at (-32000, -32000): never glue to that (measured).
		if (rect != null && (rect.w() < 64 || rect.h() < 64 || Win32.isIconic(bngHwnd))) {
			rect = null;
		}
		boolean want = transparentWindow && enabled && ControlSwitch.minecraftMode() && BngLink.get().connected()
			&& BngWorld.isLinkedWorld() && !NativeCars.active() && BngWorld.region() != null && rect != null && rect.w() >= 64 && rect.h() >= 64
			&& !Win32.isIconic(bngHwnd) && !mc.getWindow().isFullscreen();
		if (want) {
			lastWantMs = now;
		}
		if (want && !active) {
			setActive(true);
		} else if (!want && active && (!enabled || !ControlSwitch.minecraftMode() || now - lastWantMs > 1500)) {
			setActive(false);
		}
		if (active && rect != null) {
			follow(rect);
		}
	}

	private static void setActive(boolean on) {
		active = on;
		LOG.info("Overlay mode {}", on ? "ON" : "OFF");
		if (on) {
			int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
			GLFW.glfwGetWindowPos(window, x, y);
			GLFW.glfwGetWindowSize(window, w, h);
			savedX = x[0];
			savedY = y[0];
			savedW = w[0];
			savedH = h[0];
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);
			applied = null;
		} else {
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_FLOATING, GLFW.GLFW_FALSE);
			GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
			if (savedW > 0 && savedH > 0) {
				GLFW.glfwSetWindowSize(window, savedW, savedH);
				GLFW.glfwSetWindowPos(window, savedX, savedY);
			}
			applied = null;
		}
	}

	/** Undecorated, so window position/size = client area: copy BeamNG's client rectangle. */
	private static void follow(Win32.Rect r) {
		if (r.equals(applied)) {
			return;
		}
		GLFW.glfwSetWindowPos(window, r.x(), r.y());
		GLFW.glfwSetWindowSize(window, r.w(), r.h());
		applied = r;
		LOG.info("Overlay glued to BeamNG's client area {}x{} at {},{}", r.w(), r.h(), r.x(), r.y());
	}
}
