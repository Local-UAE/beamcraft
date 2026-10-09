package dev.bngmc.bridge.link;

/**
 * Constants of the crossover control protocol (UDP on 127.0.0.1). The specification is
 * beamng/docs/protocol.md; the Lua side is beamng/beamng-mod/lua/ge/extensions/mccross/bridge.lua
 * and the diagnostic client is beamng/bridge/bngdiag.py. Keep all three in sync.
 */
public final class Protocol {
	private Protocol() {
	}

	public static final int VERSION = 1;
	public static final String HOST = "127.0.0.1";
	/** BeamNG listens here; clients use an ephemeral port. Override with -Dbngbridge.port=N. */
	public static final int DEFAULT_PORT = 47020;

	/** LuaSocket's UDP receive buffer is 8192 bytes: anything sent to BeamNG must fit. */
	public static final int MAX_TO_BEAMNG = 8000;
	/** Largest datagram we accept from BeamNG. */
	public static final int MAX_FROM_BEAMNG = 65507;

	/**
	 * BeamNG counts as gone if nothing arrived for this long. Longer than BeamNG's own pauses: a
	 * car export holds it still for about 2.1 s (measured, the Porsche's 31 MB), and with a 2 s
	 * timeout the answer came in while the link was reconnecting and was thrown away.
	 */
	public static final long PEER_TIMEOUT_MS = 8000;
	/**
	 * BeamNG sends STATE 60 times a second: quiet this long, it is standing still (a car export, a
	 * spawn, a terrain build) and messages that must arrive wait (BngLink.responsive).
	 */
	public static final long RESPONSIVE_MS = 1000;
	/** How long the link keeps the session through a car export (BngLink.expectStall); the M3's took 28.8 s. */
	public static final long EXPORT_STALL_MS = 90_000;
	/** And through a spawn, which then goes on to that car's export. */
	public static final long SPAWN_STALL_MS = 60_000;
	/** While not connected, HELLO is resent this often. */
	public static final long HELLO_INTERVAL_MS = 500;
	public static final long PING_INTERVAL_MS = 250;

	// Message types (field "t").
	public static final String HELLO = "hello";
	public static final String WELCOME = "welcome";
	public static final String PING = "ping";
	public static final String PONG = "pong";
	public static final String STATE = "state";
	public static final String VEHICLES = "vehicles";
	public static final String CAMERA = "camera";
	public static final String CAMERA_RELEASE = "camera_release";
	public static final String CAMERA_TEST = "camera_test";
	public static final String DEBUG = "debug";
	public static final String RAYCOLS = "raycols";
	public static final String RAYHITS = "rayhits";
	public static final String BYE = "bye";
	public static final String ERROR = "error";
	public static final String EXPLOSION = "explosion";
	public static final String ENTER_VEHICLE = "enter_vehicle";
	public static final String IMPULSE = "impulse";
	public static final String BLOCKS = "blocks";
	public static final String VEHICLE_DRIVE = "vehicle_drive";
	public static final String VEHICLE_PLACE = "vehicle_place";
	public static final String VMESH_SUB = "vmesh_sub";
	public static final String VMESH = "vmesh";
	public static final String VTRIS = "vtris";
	public static final String VTRIS_REQ = "vtris_req";
	public static final String VEXPORT = "vexport";
	public static final String VEXPORTED = "vexported";
	public static final String RENDER_MAIN = "render_main";
	public static final String VEHICLE_ACTION = "vehicle_action";
	public static final String VLIST_REQ = "vlist_req";
	public static final String VLIST = "vlist";
	public static final String VCONFIGS_REQ = "vconfigs_req";
	public static final String VCONFIGS = "vconfigs";
	public static final String VEHICLE_SPAWN = "vehicle_spawn";
	public static final String VEHICLE_SPAWNED = "vehicle_spawned";
	public static final String VEHICLE_RESCUED = "vehicle_rescued";
	public static final String GROUND_CHUNK = "ground_chunk";
	public static final String GROUND_ON = "ground_on";
	public static final String GROUND_RESET = "ground_reset";
	public static final String TERRAIN_LOAD = "terrain_load";
	public static final String TERRAIN_LOADED = "terrain_loaded";
	public static final String TERRAIN_CELLS = "terrain_cells";
	public static final String TERRAIN_ACK = "terrain_ack";
	public static final String TERRAIN_RESET = "terrain_reset";
	/** One chunk's thin posts as boxes, canonical metres (TerrainPosts); an empty list takes them out. */
	public static final String POST_CHUNK = "post_chunk";
	public static final String POST_RESET = "post_reset";
	public static final String VEHICLE_REMOVE = "vehicle_remove";
	public static final String VEHICLE_FX = "vehicle_fx";
	public static final String TRIGGER_AIM = "trigger_aim";
	public static final String TRIGGER_HIT = "trigger_hit";
	public static final String TRIGGER_USE = "trigger_use";
}
