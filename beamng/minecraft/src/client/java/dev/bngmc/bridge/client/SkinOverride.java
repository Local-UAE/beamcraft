package dev.bngmc.bridge.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The dev client plays offline (no Microsoft login), so Minecraft gives the player the default
 * skin. With -Dbngbridge.skin=&lt;Minecraft username&gt; (scripts/run-minecraft.ps1 reads it from
 * minecraft/run/bngbridge-local.properties, which git ignores), that player's public skin is
 * fetched from Mojang's profile service and used for the local player (AbstractClientPlayerMixin).
 * No account or token is involved: skins are public.
 */
public final class SkinOverride {
	private SkinOverride() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("bngbridge");
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("bngbridge", "skins/local_player");
	private static volatile PlayerSkin skin;

	public static PlayerSkin skin() {
		return skin;
	}

	public static void start() {
		String name = System.getProperty("bngbridge.skin", "").trim();
		if (name.isEmpty()) {
			return;
		}
		if (!NAME.matcher(name).matches()) {
			LOG.warn("Ignoring bngbridge.skin: '{}' is not a Minecraft username", name);
			return;
		}
		Thread t = new Thread(() -> fetch(name), "bngbridge-skin");
		t.setDaemon(true);
		t.start();
	}

	private static void fetch(String name) {
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			JsonObject id = json(http, "https://api.mojang.com/users/profiles/minecraft/" + name);
			String uuid = id.get("id").getAsString();
			JsonObject profile = json(http, "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
			String textures = null;
			for (var p : profile.getAsJsonArray("properties")) {
				if ("textures".equals(p.getAsJsonObject().get("name").getAsString())) {
					textures = p.getAsJsonObject().get("value").getAsString();
				}
			}
			if (textures == null) {
				LOG.warn("{} has no skin on Mojang's profile service", name);
				return;
			}
			JsonObject tex = JsonParser.parseString(new String(Base64.getDecoder().decode(textures), StandardCharsets.UTF_8)).getAsJsonObject()
				.getAsJsonObject("textures").getAsJsonObject("SKIN");
			String url = tex.get("url").getAsString();
			URI uri = URI.create(url.replace("http://", "https://"));
			if (!"textures.minecraft.net".equals(uri.getHost())) {
				LOG.warn("Not downloading a skin from {}", uri.getHost());
				return;
			}
			boolean slim = tex.has("metadata") && "slim".equals(tex.getAsJsonObject("metadata").get("model").getAsString());
			byte[] png = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray()).body();
			Minecraft.getInstance().execute(() -> register(name, png, url, slim));
		} catch (Exception e) {
			LOG.warn("Could not fetch the skin of {}: {}", name, e.toString());
		}
	}

	private static JsonObject json(HttpClient http, String url) throws Exception {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
		}
		return JsonParser.parseString(r.body()).getAsJsonObject();
	}

	/** Render thread. */
	private static void register(String name, byte[] png, String url, boolean slim) {
		ByteBuffer buf = MemoryUtil.memAlloc(png.length);
		try {
			buf.put(png).flip();
			NativeImage img = NativeImage.read(buf);
			if (img.getWidth() != 64 || img.getHeight() != 64) {
				LOG.warn("Skin of {} is {}x{}, only 64x64 skins are used", name, img.getWidth(), img.getHeight());
				img.close();
				return;
			}
			Minecraft.getInstance().getTextureManager().register(TEXTURE, new DynamicTexture(img));
			skin = new PlayerSkin(TEXTURE, url, null, null, slim ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE, false);
			LOG.info("Using {}'s skin ({} model)", name, slim ? "slim" : "wide");
		} catch (Exception e) {
			LOG.warn("Could not load the skin of {}: {}", name, e.toString());
		} finally {
			MemoryUtil.memFree(buf);
		}
	}
}
