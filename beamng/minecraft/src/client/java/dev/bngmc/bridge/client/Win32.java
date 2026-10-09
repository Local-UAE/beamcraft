package dev.bngmc.bridge.client;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The few Win32 calls the overlay and the control switch need, through the JNA that ships with
 * Minecraft (jna-platform 5.14). Everything returns a harmless value off Windows or on failure:
 * the overlay just stays off.
 */
public final class Win32 {
	private Win32() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	public static final boolean AVAILABLE = Platform.get() == Platform.WINDOWS;
	private static final String BEAMNG_EXE = "beamng.drive.x64.exe";
	private static boolean failedLogged;

	/** user32 functions jna-platform 5.14's User32 doesn't bind. */
	private interface User32Ex extends StdCallLibrary {
		User32Ex INSTANCE = AVAILABLE ? Native.load("user32", User32Ex.class, W32APIOptions.DEFAULT_OPTIONS) : null;

		boolean ClientToScreen(WinDef.HWND hwnd, WinDef.POINT point);

		boolean IsIconic(WinDef.HWND hwnd);
	}

	/** Screen rectangle of a window's client area (physical pixels). */
	public record Rect(int x, int y, int w, int h) {
	}

	/**
	 * BeamNG's main window: owned by BeamNG.drive.x64.exe, visible, title starting with
	 * "BeamNG.drive" (measured: "BeamNG.drive - 0.39.4.0.20972 - RELEASE - Direct3D12"). Matching the
	 * title, not the size, finds it while minimised too (its client area is then 0x0). 0 if none.
	 */
	public static long findBeamngWindow() {
		if (!AVAILABLE) {
			return 0;
		}
		try {
			long[] best = {0, 0};
			User32.INSTANCE.EnumWindows((hwnd, data) -> {
				if (!User32.INSTANCE.IsWindowVisible(hwnd)) {
					return true;
				}
				IntByReference pid = new IntByReference();
				User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
				if (!processExe(pid.getValue()).endsWith(BEAMNG_EXE)) {
					return true;
				}
				char[] title = new char[256];
				User32.INSTANCE.GetWindowText(hwnd, title, title.length);
				if (!Native.toString(title).startsWith("BeamNG.drive")) {
					return true;
				}
				WinDef.RECT r = new WinDef.RECT();
				User32.INSTANCE.GetWindowRect(hwnd, r);
				long area = Math.max(1L, (long) (r.right - r.left) * (r.bottom - r.top));
				if (area > best[1]) {
					best[0] = Pointer.nativeValue(hwnd.getPointer());
					best[1] = area;
				}
				return true;
			}, null);
			return best[0];
		} catch (Throwable t) {
			logOnce(t);
			return 0;
		}
	}

	public static Rect clientRect(long hwnd) {
		if (!AVAILABLE || hwnd == 0) {
			return null;
		}
		try {
			WinDef.HWND h = new WinDef.HWND(new Pointer(hwnd));
			if (!User32.INSTANCE.IsWindow(h) || !User32.INSTANCE.IsWindowVisible(h)) {
				return null;
			}
			WinDef.RECT r = new WinDef.RECT();
			if (!User32.INSTANCE.GetClientRect(h, r)) {
				return null;
			}
			WinDef.POINT p = new WinDef.POINT(0, 0);
			User32Ex.INSTANCE.ClientToScreen(h, p);
			return new Rect(p.x, p.y, r.right - r.left, r.bottom - r.top);
		} catch (Throwable t) {
			logOnce(t);
			return null;
		}
	}

	/** True if the window is minimised. */
	public static boolean isIconic(long hwnd) {
		try {
			return AVAILABLE && hwnd != 0 && User32Ex.INSTANCE.IsIconic(new WinDef.HWND(new Pointer(hwnd)));
		} catch (Throwable t) {
			return false;
		}
	}

	public static long foregroundWindow() {
		try {
			WinDef.HWND h = AVAILABLE ? User32.INSTANCE.GetForegroundWindow() : null;
			return h == null ? 0 : Pointer.nativeValue(h.getPointer());
		} catch (Throwable t) {
			return 0;
		}
	}

	/** Our GLFW window as an HWND. */
	public static long glfwHwnd(long glfwWindow) {
		return AVAILABLE ? GLFWNativeWin32.glfwGetWin32Window(glfwWindow) : 0;
	}

	/**
	 * Brings a window to the front. Windows only lets the foreground process do that, so if a plain
	 * SetForegroundWindow is refused, a synthetic Alt tap first (the usual workaround: it counts as
	 * user input for this process). Returns whether the window ended up in front.
	 */
	public static boolean bringToFront(long hwnd) {
		if (!AVAILABLE || hwnd == 0) {
			return false;
		}
		try {
			WinDef.HWND h = new WinDef.HWND(new Pointer(hwnd));
			if (User32Ex.INSTANCE.IsIconic(h)) {
				User32.INSTANCE.ShowWindow(h, WinUser.SW_RESTORE);
			}
			if (User32.INSTANCE.SetForegroundWindow(h) && foregroundWindow() == hwnd) {
				return true;
			}
			WinUser.INPUT[] in = (WinUser.INPUT[]) new WinUser.INPUT().toArray(2);
			for (int i = 0; i < 2; i++) {
				in[i].type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
				in[i].input.setType("ki");
				in[i].input.ki.wVk = new WinDef.WORD(0x12);   // VK_MENU
				in[i].input.ki.dwFlags = new WinDef.DWORD(i == 0 ? 0 : 2);   // key down, KEYEVENTF_KEYUP
			}
			User32.INSTANCE.SendInput(new WinDef.DWORD(2), in, in[0].size());
			User32.INSTANCE.SetForegroundWindow(h);
			User32.INSTANCE.BringWindowToTop(h);
			return foregroundWindow() == hwnd;
		} catch (Throwable t) {
			logOnce(t);
			return false;
		}
	}

	/** True while the key is held, system-wide (GetAsyncKeyState high bit). */
	public static boolean keyDown(int vk) {
		try {
			return AVAILABLE && (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0;
		} catch (Throwable t) {
			return false;
		}
	}

	private static String processExe(int pid) {
		WinNT.HANDLE h = Kernel32.INSTANCE.OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, pid);
		if (h == null) {
			return "";
		}
		try {
			char[] buf = new char[1024];
			IntByReference len = new IntByReference(buf.length);
			if (Kernel32.INSTANCE.QueryFullProcessImageName(h, 0, buf, len)) {
				return Native.toString(buf).toLowerCase(java.util.Locale.ROOT);
			}
			return "";
		} finally {
			Kernel32.INSTANCE.CloseHandle(h);
		}
	}

	private static void logOnce(Throwable t) {
		if (!failedLogged) {
			failedLogged = true;
			LOG.warn("Win32 call failed, overlay features disabled: {}", t.toString());
		}
	}
}
