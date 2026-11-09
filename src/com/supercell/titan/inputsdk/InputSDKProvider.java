package com.supercell.titan.inputsdk;

import android.content.Context;
import android.util.Log;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local registry for native keyboard bindings. PC service remapping is unavailable. */
public final class InputSDKProvider {
    private static final class Binding {
        final String name, identifier;
        final int[] members;
        final boolean remappable;
        Binding(String name, int[] members, boolean remappable, String identifier) {
            this.name = name;
            this.members = members == null ? new int[0] : members.clone();
            this.remappable = remappable;
            this.identifier = identifier;
        }
    }
    private static final Map<Integer, Binding> actions = new LinkedHashMap<>();
    private static final Map<Integer, Binding> groups = new LinkedHashMap<>();
    private static final Map<Integer, Binding> contexts = new LinkedHashMap<>();
    private static Binding inputMap;
    private static long mapId;
    private static int activeContext;

    public static synchronized void addAction(String name, int id, int[] keys, boolean remap, String identifier) {
        actions.put(id, new Binding(name, keys, remap, identifier));
    }
    public static synchronized void addGroup(String name, int id, int[] actionIds, boolean remap, String identifier) {
        groups.put(id, new Binding(name, actionIds, remap, identifier));
    }
    public static synchronized void addContext(String name, int id, int[] groupIds, String identifier) {
        contexts.put(id, new Binding(name, groupIds, false, identifier));
    }
    public static synchronized void registerInputMap(Context context, String name, long id, boolean remap, int[] keys) {
        inputMap = new Binding(name, keys, remap, name);
        mapId = id;
        Log.i("OfflineInput", "Local map " + mapId + ": " + actions.size() + " actions, " + groups.size() + " groups; PC remapping unavailable");
    }
    public static synchronized void setInputContext(Context context, int id) {
        if (contexts.containsKey(id)) activeContext = id;
    }
}
