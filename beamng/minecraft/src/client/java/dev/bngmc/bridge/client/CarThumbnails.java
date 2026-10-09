package dev.bngmc.bridge.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BeamNG's preview pictures for the car picker as GUI textures. carselect.lua copies them out of
 * the vehicle zips into BeamNG's user folder (JPEG or PNG, 500 x 281); here they are decoded off
 * the render thread, shrunk and registered once.
 */
final class CarThumbnails {
	private CarThumbnails() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	static final int W = 256, H = 144;

	private static final Map<String, ResourceLocation> READY = new ConcurrentHashMap<>();
	private static final Set<String> STARTED = ConcurrentHashMap.newKeySet();
	private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "bngbridge-thumbs");
		t.setDaemon(true);
		return t;
	});

	/** The picture for this file, or null while it loads or if there is none. */
	static ResourceLocation get(String file) {
		if (file == null || file.isEmpty()) {
			return null;
		}
		ResourceLocation rl = READY.get(file);
		if (rl == null && STARTED.add(file)) {
			DECODER.execute(() -> decode(file));
		}
		return rl;
	}

	private static void decode(String file) {
		try {
			BufferedImage src = ImageIO.read(new File(file));
			if (src == null) {
				LOG.warn("Car picture {} is not an image Java can read", new File(file).getName());
				return;
			}
			BufferedImage small = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = small.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(src, 0, 0, W, H, null);
			g.dispose();
			NativeImage img = new NativeImage(W, H, false);
			for (int y = 0; y < H; y++) {
				for (int x = 0; x < W; x++) {
					int argb = small.getRGB(x, y);
					// NativeImage wants ABGR
					img.setPixelRGBA(x, y, (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16));
				}
			}
			Minecraft.getInstance().execute(() -> {
				ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("bngbridge", "thumb/" + NEXT.getAndIncrement());
				Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
				READY.put(file, rl);
			});
		} catch (Exception e) {
			LOG.warn("Could not read car picture {}: {}", new File(file).getName(), e.toString());
		}
	}
}
