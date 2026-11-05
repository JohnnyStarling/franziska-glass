package lab.franziska.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Declarative M3 module boundary; no module requests undeclared capture or network use. */
public final class ModuleContract {
    public enum State { OFF, STARTING, ACTIVE, DEGRADED, STOPPING, ERROR }

    public final String id;
    public final String version;
    public final List<String> capabilities;
    public final List<String> permissions;
    public final boolean internet;
    public final boolean gateway;
    public final List<String> inputs;
    public final List<String> outputs;
    public final int maxFrames;
    public final int maxAudioChunks;
    public final String lossPolicy;
    public final String storage;
    public final String cost;

    public ModuleContract(String id, String version, List<String> capabilities,
                          List<String> permissions, boolean internet, boolean gateway,
                          List<String> inputs, List<String> outputs, int maxFrames,
                          int maxAudioChunks, String lossPolicy, String storage,
                          String cost) throws ProtocolException {
        if (id == null || !id.matches("[a-z0-9.-]{1,64}")) {
            throw new ProtocolException("MODULE_ID");
        }
        if (version == null || version.length() > 32) {
            throw new ProtocolException("MODULE_VERSION");
        }
        if (maxFrames < 0 || maxFrames > 4
                || maxAudioChunks < 0 || maxAudioChunks > 32) {
            throw new ProtocolException("MODULE_LIMIT");
        }
        this.id = id;
        this.version = version;
        this.capabilities = copy(capabilities);
        this.permissions = copy(permissions);
        this.internet = internet;
        this.gateway = gateway;
        this.inputs = copy(inputs);
        this.outputs = copy(outputs);
        this.maxFrames = maxFrames;
        this.maxAudioChunks = maxAudioChunks;
        this.lossPolicy = lossPolicy;
        this.storage = storage;
        this.cost = cost;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public Map<String, Object> payload() {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("id", id);
        payload.put("version", version);
        payload.put("capabilities", capabilities);
        payload.put("permissions", permissions);
        payload.put("internet", Boolean.valueOf(internet));
        payload.put("gateway", Boolean.valueOf(gateway));
        payload.put("inputs", inputs);
        payload.put("outputs", outputs);
        payload.put("max_frames", Long.valueOf(maxFrames));
        payload.put("max_audio_chunks", Long.valueOf(maxAudioChunks));
        payload.put("start", "explicit");
        payload.put("stop", "priority_terminal");
        payload.put("link_loss", lossPolicy);
        payload.put("storage", storage);
        payload.put("cost", cost);
        return payload;
    }

    public static List<ModuleContract> foundation() throws ProtocolException {
        List<String> none = Collections.emptyList();
        ArrayList<ModuleContract> modules = new ArrayList<ModuleContract>();
        modules.add(new ModuleContract("diagnostics", "1", list("status", "ping"),
                none, false, false, none, list("state"), 0, 0,
                "degrade", "technical-events-only", "none"));
        modules.add(new ModuleContract("demo-cards", "1", list("ui.card"),
                none, false, false, list("card"), list("ui.card"), 0, 0,
                "latest-state", "none", "none"));
        modules.add(new ModuleContract("demo-vectors", "1", list("ui.vector_scene"),
                none, false, false, list("vector-scene"), list("ui.vector_scene"),
                0, 0, "latest-state", "none", "none"));
        return Collections.unmodifiableList(modules);
    }

    private static List<String> list(String... values) {
        ArrayList<String> result = new ArrayList<String>();
        Collections.addAll(result, values);
        return result;
    }
}
