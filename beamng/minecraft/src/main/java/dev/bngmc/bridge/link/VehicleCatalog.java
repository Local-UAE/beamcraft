package dev.bngmc.bridge.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BeamNG's own vehicle list for the car picker (carselect.lua): every model with its preview
 * picture, then one model's configurations when the picker opens it. BeamNG sends each list in
 * pages ({page, pages, ...}); a list is only shown once all of its pages are in.
 */
public final class VehicleCatalog {
	public record Model(String key, String name, String brand, String type, int configs, String defaultConfig, String thumb) {
		/** "Civetta Scintilla", or just the name when BeamNG gives no brand. */
		public String title() {
			return brand.isEmpty() ? name : brand + " " + name;
		}
	}

	public record Config(String key, String name, boolean isDefault, String thumb) {
	}

	/** Types shown unless the picker asks for everything (props, trailers, debug objects). */
	public static final Set<String> DRIVEN_TYPES = Set.of("Car", "Truck");

	private static final VehicleCatalog INSTANCE = new VehicleCatalog();

	public static VehicleCatalog get() {
		return INSTANCE;
	}

	public static void install(BngLink link) {
		link.onMessage(Protocol.VLIST, env -> INSTANCE.acceptModels(env.body()));
		link.onMessage(Protocol.VCONFIGS, env -> INSTANCE.acceptConfigs(env.body()));
	}

	/** One list arriving in pages. */
	private static final class Pages<T> {
		final List<List<T>> parts = new ArrayList<>();
		int got;

		Pages(int pages) {
			for (int i = 0; i < pages; i++) {
				parts.add(null);
			}
		}

		/** Returns the whole list once every page is in, else null. */
		List<T> put(int page, List<T> part) {
			if (page < 1 || page > parts.size()) {
				return null;
			}
			if (parts.get(page - 1) == null) {
				got++;
			}
			parts.set(page - 1, part);
			if (got < parts.size()) {
				return null;
			}
			List<T> all = new ArrayList<>();
			parts.forEach(all::addAll);
			return List.copyOf(all);
		}
	}

	private volatile List<Model> models;
	private Pages<Model> modelPages;
	private final Map<String, List<Config>> configs = new ConcurrentHashMap<>();
	private final Map<String, Pages<Config>> configPages = new ConcurrentHashMap<>();

	VehicleCatalog() {
	}

	/** Every model, sorted as BeamNG sent them (brand, name); null until the list is in. */
	public List<Model> models() {
		return models;
	}

	/** One model's configurations, the default first; null until they are in. */
	public List<Config> configs(String model) {
		return configs.get(model);
	}

	public boolean requestModels(BngLink link) {
		return link.send(Protocol.VLIST_REQ, new JsonObject());
	}

	public boolean requestConfigs(BngLink link, String model) {
		JsonObject msg = new JsonObject();
		msg.addProperty("model", model);
		return link.send(Protocol.VCONFIGS_REQ, msg);
	}

	synchronized void acceptModels(JsonObject body) {
		int page = intOf(body, "page", 1), pages = pagesOf(body);
		if (page == 1 || modelPages == null || modelPages.parts.size() != pages) {
			modelPages = new Pages<>(pages);
		}
		List<Model> part = new ArrayList<>();
		for (JsonElement e : arrayOf(body, "models")) {
			if (e.isJsonObject()) {
				part.add(parseModel(e.getAsJsonObject()));
			}
		}
		List<Model> all = modelPages.put(page, part);
		if (all != null) {
			models = all;
			modelPages = null;
		}
	}

	synchronized void acceptConfigs(JsonObject body) {
		String model = strOf(body, "model");
		if (model.isEmpty()) {
			return;
		}
		int page = intOf(body, "page", 1), pages = pagesOf(body);
		Pages<Config> p = configPages.get(model);
		if (page == 1 || p == null || p.parts.size() != pages) {
			p = new Pages<>(pages);
			configPages.put(model, p);
		}
		List<Config> part = new ArrayList<>();
		for (JsonElement e : arrayOf(body, "configs")) {
			if (e.isJsonObject()) {
				part.add(parseConfig(e.getAsJsonObject()));
			}
		}
		List<Config> all = p.put(page, part);
		if (all != null) {
			configs.put(model, all);
			configPages.remove(model);
		}
	}

	static Model parseModel(JsonObject o) {
		return new Model(strOf(o, "k"), strOf(o, "name"), strOf(o, "brand"), strOf(o, "type"), intOf(o, "configs", 0), strOf(o, "def"),
			strOf(o, "thumb"));
	}

	static Config parseConfig(JsonObject o) {
		return new Config(strOf(o, "k"), strOf(o, "name"), o.has("def") && o.get("def").isJsonPrimitive() && o.get("def").getAsBoolean(),
			strOf(o, "thumb"));
	}

	/** The picker's filter: words of the query all found in brand, name or key; cars and trucks only unless everything. */
	public static List<Model> filter(List<Model> all, String query, boolean everything) {
		String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		List<Model> out = new ArrayList<>();
		for (Model m : all) {
			if (!everything && !DRIVEN_TYPES.contains(m.type())) {
				continue;
			}
			String hay = (m.brand() + " " + m.name() + " " + m.key()).toLowerCase(Locale.ROOT);
			boolean match = true;
			for (String w : words) {
				if (!w.isEmpty() && !hay.contains(w)) {
					match = false;
					break;
				}
			}
			if (match) {
				out.add(m);
			}
		}
		return out;
	}

	private static String strOf(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}

	private static final int MAX_PAGES = 1000;   // 40 per page: far more than any install has

	private static int pagesOf(JsonObject o) {
		return Math.max(1, Math.min(MAX_PAGES, intOf(o, "pages", 1)));
	}

	private static int intOf(JsonObject o, String k, int def) {
		return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber() ? o.get(k).getAsInt() : def;
	}

	private static JsonArray arrayOf(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
	}
}
