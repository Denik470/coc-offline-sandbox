package lab.coc.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Categorised offline tools for the local village and the original matchmaking.
 * Top-ups edit the save files (save-home.json / save-avatar.bin) after native code flushed the current state and paused autosave,
 * then use the in-place home transition so the game loads the edited saves.
 */
public final class OfflineTools {
    private static final String[] LAYOUT_ARRAYS = {"buildings", "traps", "decos", "heroFlags"};

    private static native void requestSaveAndPause();
    private static native boolean isSaveAndPauseDone();
    private static native void requestReloadHome();


    /** Startup rule (before the home is loaded): every super troop licence active for its normal duration from now, obstacles removed (globals MAX_ACTIVE_SUPER_LICENCES is raised to 17 at build time). */
    public static void applyStartupRules(Activity a) {
        File f = new File(a.getFilesDir(), "save-home.json");
        if (!f.exists()) return;
        try {
            JSONObject home = new JSONObject(new String(read(f), StandardCharsets.UTF_8));
            JSONArray ends = new JSONArray();
            long end = System.currentTimeMillis() / 1000 + LICENCE_HOURS * 3600L; // renewed on every launch: always active, timer shows the normal duration
            for (int i = 0; i < SUPER_LICENCES; i++) ends.put(59000000 + i).put(end);
            home.put("superlicences", new JSONObject().put("licence_ends", ends));
            // Sandbox: every skin of logic/skins.csv owned (ids 52000000 + row), so all hero skins can be selected in the hero screen.
            // Skins whose files were left out of the APK (assets/skin-drop.json, size limit) are not owned, and a hero wearing one (an older
            // save) gets that hero's default skin, in the home and in the avatar save; a missing skin model would crash the village load.
            java.util.Set<Long> dropped = new java.util.HashSet<>();
            java.util.Map<Long, Long> defaults = new java.util.HashMap<>();
            try {
                JSONObject drop = new JSONObject(new String(asset(a, "skin-drop.json"), StandardCharsets.UTF_8));
                JSONArray d = drop.getJSONArray("drop_ids"); for (int i = 0; i < d.length(); i++) dropped.add(d.getLong(i));
                JSONObject df = drop.optJSONObject("default_for");
                for (java.util.Iterator<String> it = df == null ? java.util.Collections.<String>emptyIterator() : df.keys(); it.hasNext(); ) { String k = it.next(); defaults.put(Long.parseLong(k), df.getLong(k)); }
            } catch (Exception ignored) {}
            if (!dropped.isEmpty()) {
                replaceSkinRefs(home, dropped, defaults);
                File av = new File(a.getFilesDir(), "save-avatar.bin");
                if (av.exists()) {
                    byte[] b = read(av); boolean changed = false;
                    for (Op op : parseOps(b)) if (op.method == 0x50 && dropped.contains(op.value)) { Long def = defaults.get(op.value); if (def != null) { setValue(b, op, def); changed = true; } }
                    if (changed) write(av, b);
                }
            }
            JSONArray skins = new JSONArray();
            for (int i = 0; i < SKIN_COUNT; i++) if (!dropped.contains(52000000L + i)) skins.put(52000000 + i);
            home.put("skins", skins);
            // LogicLevel::load reads the owned village backgrounds from "vbgs"
            // (ELF f07d20), retaining "sbgs" and the player's selected scenery.
            try (java.io.InputStream in = a.getAssets().open("scenery.json")) {
                ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) > 0) data.write(buffer, 0, n);
                JSONArray catalog = new JSONArray(new String(data.toByteArray(), StandardCharsets.UTF_8));
                JSONArray backgrounds = new JSONArray();
                for (int i = 0; i < catalog.length(); i++) backgrounds.put(catalog.getJSONObject(i).getInt("id"));
                home.put("vbgs", backgrounds);
            }
            applySceneryChoice(a, home);
            clearEmptyLayoutDrafts(home);
            // Loot cart ("Собрать", village object LootCart 39000022): nothing to collect offline, removed from the village objects.
            JSONArray vobjs = home.optJSONArray("vobjs");
            if (vobjs != null) {
                JSONArray keptObjects = new JSONArray();
                for (int i = 0; i < vobjs.length(); i++) if (vobjs.getJSONObject(i).optLong("data") != 39000022L) keptObjects.put(vobjs.getJSONObject(i));
                home.put("vobjs", keptObjects);
            }
            // Captured clan-games rewards are obsolete in the local sandbox.
            // Absence lets the native reader use its default, inactive state.
            home.remove("alliance_games");
            // Sandbox: no obstacles at all (trees, stones, gem boxes, seasonal ones), on the border too.
            home.put("obstacles", new JSONArray());
            JSONObject respawn = home.optJSONObject("respawnVars");
            // time_to_gembox_drop beyond the drop period triggers an immediate gem box, so it is kept inside the period.
            if (respawn != null) { respawn.put("secondsFromLastRespawn", 0); respawn.put("time_to_gembox_drop", 1000000000); respawn.put("time_in_gembox_period", 0); }
            write(f, home.toString().getBytes(StandardCharsets.UTF_8));
            fillCastle(a, home);
        } catch (Exception e) { android.util.Log.e("OfflineTools", "startup rules failed", e); }
    }

    /**
     * Free full clan castle: the castle reinforcements set in the army (active_army_loadout, slot type 3) are the castle's content in
     * the avatar save (alliance units), so the castle is always full without spending raid medals. files/cc-fill.bin holds the same
     * list for probe.cpp, which puts it back after every battle.
     */
    private static void fillCastle(Activity a, JSONObject home) { fillCastle(a, home, true); }

    private static void fillCastle(Activity a, JSONObject home, boolean save) {
        try {
            JSONObject ml = home.optJSONObject("active_army_loadout");
            JSONArray cc = ml == null ? null : ml.getJSONArray("slots_by_type").optJSONArray(3);
            if (cc == null || cc.length() == 0) return;
            JSONArray units = new JSONArray();
            for (int i = 0; i < cc.length(); i++) {
                JSONObject u = cc.getJSONObject(i);
                units.put(new JSONObject().put("id", u.getLong("id")).put("cnt", u.optLong("m_count", 1)).put("lvl", u.optLong("m_level", 0)));
            }
            File av = new File(a.getFilesDir(), "save-avatar.bin");
            if (save && av.exists()) write(av, replaceAllianceUnits(read(av), units));
            write(new File(a.getFilesDir(), "cc-fill.bin"), allianceListOps(units));
        } catch (Exception e) { android.util.Log.e("OfflineTools", "castle fill", e); }
    }

    /** Alliance unit list ops: 0x50 N, N x (0x50 id, 0x88 count, 0x88 level). */
    private static byte[] allianceListOps(JSONArray units) throws Exception {
        ByteBuffer list = ByteBuffer.allocate(10 + units.length() * 30).order(ByteOrder.LITTLE_ENDIAN);
        list.putShort((short) 0x50).putLong(units.length());
        for (int i = 0; i < units.length(); i++) {
            JSONObject u = units.getJSONObject(i);
            list.putShort((short) 0x50).putLong(u.getLong("id")); list.putShort((short) 0x88).putLong(u.getLong("cnt")); list.putShort((short) 0x88).putLong(u.optLong("lvl", 0));
        }
        return java.util.Arrays.copyOf(list.array(), list.position());
    }

    /** The avatar save with its alliance unit list (the clan castle content) replaced by units. */
    private static byte[] replaceAllianceUnits(byte[] b, JSONArray units) throws Exception {
        List<Op> ops = parseOps(b);
        int at = -1;
        for (int i = 0; i + 1 < ops.size() && at < 0; i++) {
            Op c = ops.get(i);
            if (c.method != 0x50 || c.value < 0 || c.value > 50 || i + 3 * c.value >= ops.size()) continue;
            boolean ok = c.value > 0;
            for (int k = 0; k < c.value && ok; k++) {
                Op id = ops.get(i + 1 + 3 * k), cnt = ops.get(i + 2 + 3 * k), lvl = ops.get(i + 3 + 3 * k);
                ok = id.method == 0x50 && ((id.value >= 4000000 && id.value < 5000000) || (id.value >= 26000000 && id.value < 27000000)) && cnt.method == 0x88 && lvl.method == 0x88;
            }
            if (ok) at = i;
        }
        if (at < 0) throw new IllegalArgumentException("Список войск крепости не найден в сохранении");
        int old = (int) ops.get(at).value;
        int startOff = ops.get(at).offset - 2, endIndex = at + 1 + 3 * old;
        int endOff = endIndex < ops.size() ? ops.get(endIndex).offset - 2 : b.length;
        byte[] list = allianceListOps(units);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(b, 0, startOff); o.write(list); o.write(b, endOff, b.length - endOff);
        byte[] r = o.toByteArray();
        ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).putInt(0, ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(0) - 3 * old + 3 * units.length());
        parseOps(r);  // refuse a broken rewrite
        return r;
    }
    /** An editor draft ("Закончить позже") is layout_state[N] = 1 plus "eNx" positions on the objects. A flag without any positions (left by
     *  edits of war slots before they were editable offline) makes the editor restore an empty draft with everything in storage. */
    private static void clearEmptyLayoutDrafts(JSONObject home) throws Exception {
        JSONArray state = home.optJSONArray("layout_state");
        for (int slot = 0; state != null && slot < state.length(); slot++) {
            if (state.optInt(slot) != 1) continue;
            boolean any = false;
            for (String arr : LAYOUT_ARRAYS) {
                JSONArray items = home.optJSONArray(arr);
                for (int i = 0; items != null && i < items.length() && !any; i++) any = items.getJSONObject(i).has(slot == 0 ? "emx" : "e" + slot + "x");
            }
            if (!any) state.put(slot, 0);
        }
    }

    /** The scenery picker changes the shown scenery ("bg"[0]); battles and reloads can replace it in the save. The sandbox remembers the
     *  last scenery seen on the home screen (files/scenery-choice.txt) and puts it back into "bg"[0] before the home loads. Nothing else is
     *  touched ("selected_random_background" is the server's rotation list and stays as the game keeps it). */
    private static void applySceneryChoice(Activity a, JSONObject home) throws Exception {
        File f = new File(a.getFilesDir(), "scenery-choice.txt");
        JSONArray bg = home.optJSONArray("bg");
        if (!f.exists() || bg == null || bg.length() == 0) return;
        long chosen = Long.parseLong(new String(read(f), StandardCharsets.UTF_8).trim());
        bg.put(0, chosen);
    }

    // While playing: the autosaved scenery of the home screen is recorded (no reload; the picker already shows it).
    private static int sceneryCheck;
    private static volatile boolean sceneryRunning;
    private static void checkSceneryChoice(Activity a) {
        if (sceneryRunning || ++sceneryCheck % 300 != 0 || isBattleActive() || !homeReady()) return;
        sceneryRunning = true;
        new Thread(() -> {
            try {
                JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
                JSONArray bg = home.optJSONArray("bg");
                if (bg != null && bg.length() > 0) {
                    File f = new File(a.getFilesDir(), "scenery-choice.txt");
                    String value = String.valueOf(bg.getLong(0));
                    if (!f.exists() || !new String(read(f), StandardCharsets.UTF_8).trim().equals(value)) write(f, value.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) { android.util.Log.e("OfflineTools", "scenery", e); }
            finally { sceneryRunning = false; }
        }).start();
    }

    /** Replaces every number that is a dropped skin id (anywhere in the home JSON) with the hero's default skin. */
    private static void replaceSkinRefs(Object node, java.util.Set<Long> dropped, java.util.Map<Long, Long> defaults) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next(); Object v = o.get(k);
                if (v instanceof Number && dropped.contains(((Number) v).longValue()) && defaults.containsKey(((Number) v).longValue())) o.put(k, (long) defaults.get(((Number) v).longValue()));
                else replaceSkinRefs(v, dropped, defaults);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.get(i);
                if (v instanceof Number && dropped.contains(((Number) v).longValue()) && defaults.containsKey(((Number) v).longValue())) arr.put(i, (long) defaults.get(((Number) v).longValue()));
                else replaceSkinRefs(v, dropped, defaults);
            }
        }
    }

    private static final int SKIN_COUNT = 449;              // rows of logic/skins.csv
    private static final int SUPER_LICENCES = 17;           // rows of logic/super_licences.csv
    private static final int LICENCE_HOURS = 72;            // super_licences.csv DurationH

    private OfflineTools() {}

    private static native boolean isBattleActive();
    private static native void requestRestartAttack();
    private static native void setBattleSpeed(int speed);
    private static native void requestReplay(String name);
    private static native void setLiveResource(int row, int count);
    private static native void setLiveGems(int gems);
    private static native void setBattleModifier(int row);
    private static native void setLiveEquipmentLevel(int id, int levelIndex);
    private static final String[] MODIFIERS = {"Без модификатора", "Легендарная 1 (−1)", "Легендарная 2 (−2)", "Легендарная 3 (−3)", "Сложный режим (киберспорт)"};
    private static final int[] MODIFIER_ROWS = {-1, 1, 2, 3, 0};  // battle_modifiers.csv rows

    private static int savedModifier(Activity a) {
        try { return Integer.parseInt(new String(read(new File(a.getFilesDir(), "modifier.txt")), StandardCharsets.UTF_8).trim()); } catch (Exception e) { return 0; }
    }
    private static void chooseModifier(Activity a) {
        int cur = savedModifier(a); String[] items = MODIFIERS.clone(); items[cur] = items[cur] + " ✓";
        cocDialog(a, "Модификатор боя", "Применяется ко всем атакам песочницы", items, which -> {
            try { write(new File(a.getFilesDir(), "modifier.txt"), String.valueOf(which).getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
            setBattleModifier(MODIFIER_ROWS[which]); Toast.makeText(a, MODIFIERS[which], Toast.LENGTH_SHORT).show();
        });
    }
    private static native void setLiveUnitLevel(int id, int levelIndex);
    private static native void setLiveBuildingLevel(int instanceId, int levelIndex);
    private static native void setLiveHeroLevel(int hallId, int slot, int heroId, int levelIndex);
    private static native boolean isReplayActive();
    private static native void requestReplayExit();
    private static android.widget.TextView replayExitButton;
    // Clan chat button (left side, measured on 2800x1272): the sandbox puts its replays button there, drawn like the HUD buttons.
    private static final android.graphics.Rect REPLAYS_BUTTON = new android.graphics.Rect(157, 551, 333, 726);
    // Build button of the home HUD (2800x1272, bottom right corner, right-anchored): the sandbox menu button takes its place.
    private static final android.graphics.Rect BUILD_BUTTON = new android.graphics.Rect(2411, 1015, 2647, 1247);
    private static int battleSpeed = 1;
    private static android.widget.TextView speedButton;

    private static native int openPopupCount();
    private static native int socialTab();
    private static native void setMagicItemCaps(int[] ids, int[] caps);
    // Content area of the social popup (2800x1272, centred horizontally): the sandbox profile panel covers the profile tab there.
    private static final android.graphics.Rect SOCIAL_CONTENT = new android.graphics.Rect(510, 170, 2294, 1214);
    private static FrameLayout profilePanel;
    private static final int PROFILE_PAGE = 6;
    private static native boolean isAttackMenuOpen();
    private static native long selectedBuilding();
    private static View buildingLevelButton;
    // HUD check (probe.cpp hudShown): the game's own build button and every display object above it are visible, unscaled and on the stage.
    // The game hides or slides its HUD out in edit mode, the shop, battles and side menus; nothing depends on screen pixels or colours.
    private static native boolean hudShown();
    private static native void setScreenAspect(float aspect);
    // The game's HUD lives on a stage 640 units high scaled to the screen height; its buttons move with the screen shape. The sandbox's
    // buttons and the HUD probe are placed relative to the game's own buttons (probe.cpp hudAnchors: stage x, y of the build, clan and
    // gems "+" buttons), with offsets in stage units measured once on a 2800x1272 screen. Until the anchors are known the old fixed
    // positions are used.
    private static native float[] hudAnchors();
    private static final float STAGE_H = 640f;
    private static final float[] BUILD_REL = {2.2f, -59.3f, 120.9f, 57.4f}, CLAN_REL = {17.9f, -43.4f, 106.4f, 44.7f};
    private static View toolsButton, replaysButton;
    private static float[] placedAnchors;
    /** rel (stage units, left/top/right/bottom from an anchor at ax, ay) to screen pixels for a view height h. */
    private static android.graphics.Rect fromAnchor(float ax, float ay, float[] rel, int h) {
        float k = h / STAGE_H;
        return new android.graphics.Rect(Math.round((ax + rel[0]) * k), Math.round((ay + rel[1]) * k), Math.round((ax + rel[2]) * k), Math.round((ay + rel[3]) * k));
    }
    private static void placeAt(View v, android.graphics.Rect r) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(r.width(), r.height(), Gravity.TOP | Gravity.START);
        lp.leftMargin = r.left; lp.topMargin = r.top; v.setLayoutParams(lp);
    }
    /** Moves the sandbox HUD buttons onto the game's buttons when the anchors change (screen shape, first frames). */
    private static void placeHudButtons(View gameView) {
        float[] an = hudAnchors();
        if (an == null || an[0] == 0 || an[3] == 0 || java.util.Arrays.equals(an, placedAnchors) || gameView.getHeight() == 0) return;
        placedAnchors = an;
        int h = gameView.getHeight();
        if (toolsButton != null) placeAt(toolsButton, fromAnchor(an[0], an[1], BUILD_REL, h));
        if (replaysButton != null) placeAt(replaysButton, fromAnchor(an[2], an[3], CLAN_REL, h));
    }
    // Screen positions above were measured on a 2800x1272 screen. The game's HUD scales with the screen height; elements on the right side
    // stay anchored to the right edge, so other screens map them by height scale, counted from the matching edge.
    private static final float REF_W = 2800f, REF_H = 1272f;
    private static android.graphics.Rect fitRight(android.graphics.Rect r, int w, int h) {
        float k = h / REF_H;
        return new android.graphics.Rect(Math.round(w - (REF_W - r.left) * k), Math.round(r.top * k), Math.round(w - (REF_W - r.right) * k), Math.round(r.bottom * k));
    }
    private static boolean loopStarted;
    private static native void requestLayoutEditor();
    // The sword-and-list button of the "Сражение" card (screen px) opens the layout editor in the sandbox.
    private static final int[] LOG_BUTTON = {246, 218, 224, 138};
    private static View logButtonSpot;

    // Resource "+" buttons in the HUD style (screen px on this device, 2800x1272 landscape): left end of the gold / elixir / dark elixir bars,
    // and the original green "+" of the gems bar. Shown only on the home screen with no popup open.
    private static final int[][] PLUS = {{2183, 40, 62}, {2183, 127, 62}, {2298, 213, 62}};
    private static final int[] GEMS_PLUS = {2288, 300, 62};
    private static final String[] RESOURCE_NAMES = {"золото", "эликсир", "тёмный эликсир"};
    private static final List<View> plusButtons = new ArrayList<>();

    public static void install(Activity activity, View gameView) {
        FrameLayout root = new FrameLayout(activity);
        root.addView(gameView);
        plusButtons.clear();
        // The sandbox menu opens from the big button in the bottom right corner, in place of the build button (hidden by probe.cpp).
        float kh = Math.min(activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels) / REF_H;
        View tools = SandboxShop.hudButton(activity, "Песочница", "button", true);
        tools.setOnClickListener(v -> showDashboard(activity));
        FrameLayout.LayoutParams toolsLayout = new FrameLayout.LayoutParams(Math.round(BUILD_BUTTON.width() * kh), Math.round(BUILD_BUTTON.height() * kh), Gravity.TOP | Gravity.END);
        toolsLayout.rightMargin = Math.round((REF_W - BUILD_BUTTON.right) * kh); toolsLayout.topMargin = Math.round(BUILD_BUTTON.top * kh);
        root.addView(tools, toolsLayout); tools.setVisibility(View.GONE); plusButtons.add(tools); toolsButton = tools;
        android.widget.TextView buildingTools = label(activity, "Уровень здания", 11, Color.WHITE, true);
        buildingTools.setBackground(box(0xFF7CC23B, 0xFF2F5E12, 16));
        buildingTools.setOnClickListener(v -> chooseSelectedBuilding(activity, selectedBuilding()));
        FrameLayout.LayoutParams buildingLayout = new FrameLayout.LayoutParams(500, 88, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        buildingLayout.topMargin = 120; root.addView(buildingTools, buildingLayout);
        buildingTools.setVisibility(View.GONE); buildingLevelButton = buildingTools;
        logButtonSpot = new View(activity);
        logButtonSpot.setOnClickListener(v -> requestLayoutEditor());
        float k = Math.min(activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels) / REF_H;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(LOG_BUTTON[2] * k), Math.round(LOG_BUTTON[3] * k));
        lp.leftMargin = Math.round(LOG_BUTTON[0] * k); lp.topMargin = Math.round(LOG_BUTTON[1] * k);
        root.addView(logButtonSpot, lp);
        logButtonSpot.setVisibility(View.GONE);
        // Recent battle replays, in place of the (offline useless) clan chat button.
        float kr = Math.min(activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels) / REF_H;
        View replays = SandboxShop.hudButton(activity, "Повторы", "replays");
        replays.setOnClickListener(v -> showReplays(activity));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(Math.round(REPLAYS_BUTTON.width() * kr), Math.round(REPLAYS_BUTTON.height() * kr));
        rp.leftMargin = Math.round(REPLAYS_BUTTON.left * kr); rp.topMargin = Math.round(REPLAYS_BUTTON.top * kr);
        root.addView(replays, rp); replays.setVisibility(View.GONE); plusButtons.add(replays); replaysButton = replays;
        // Battle speed button (x1 -> x2 -> x4), shown during battles on the right side.
        speedButton = label(activity, "×1", 16, Color.WHITE, true);
        speedButton.setBackground(box(0xCC448BBD, 0xFF203E58, 18));
        speedButton.setOnClickListener(v -> {
            battleSpeed = battleSpeed == 1 ? 2 : battleSpeed == 2 ? 4 : 1;
            setBattleSpeed(battleSpeed); speedButton.setText("×" + battleSpeed);
        });
        float ks = Math.min(activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels) / REF_H;
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(Math.round(150 * ks), Math.round(110 * ks), Gravity.END | Gravity.CENTER_VERTICAL);
        sp.rightMargin = Math.round(40 * ks); sp.topMargin = -Math.round(120 * ks);
        root.addView(speedButton, sp); speedButton.setVisibility(View.GONE);
        replayExitButton = label(activity, "Выйти", 9, Color.WHITE, true);
        replayExitButton.setBackground(box(0xFFD8352A, 0xFF5A0E08, 12)); replayExitButton.setSingleLine(true); replayExitButton.setPadding(24, 0, 24, 0);
        replayExitButton.setOnClickListener(v -> requestReplayExit());
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, Math.round(64 * ks), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ep.topMargin = Math.round(8 * ks);
        root.addView(replayExitButton, ep); replayExitButton.setVisibility(View.GONE);
        profilePanel = new FrameLayout(activity);
        {
            float kp = Math.min(activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels) / REF_H;
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Math.round(SOCIAL_CONTENT.width() * kp), Math.round(SOCIAL_CONTENT.height() * kp), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            pp.topMargin = Math.round(SOCIAL_CONTENT.top * kp);
            root.addView(profilePanel, pp); profilePanel.setVisibility(View.GONE);
        }
        activity.setContentView(root);
        setBattleModifier(MODIFIER_ROWS[Math.max(0, Math.min(MODIFIER_ROWS.length - 1, savedModifier(activity)))]);
        // Endless magic items: every item is kept at its storage limit (probe.cpp refillMagicItems).
        try {
            JSONArray magic = new JSONArray(new String(asset(activity, "magic-items.json"), StandardCharsets.UTF_8));
            int[] ids = new int[magic.length()], caps = new int[magic.length()];
            for (int i = 0; i < magic.length(); i++) { ids[i] = magic.getJSONArray(i).getInt(0); caps[i] = magic.getJSONArray(i).getInt(1); }
            setMagicItemCaps(ids, caps);
        } catch (Exception e) { android.util.Log.w("OfflineTools", "magic items", e); }
        if (loopStarted) return;
        loopStarted = true;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                placeHudButtons(gameView);
                if (gameView.getHeight() > 0) setScreenAspect((float) gameView.getWidth() / gameView.getHeight());
                boolean show = homeReady() && openPopupCount() == 0 && hudShown();
                for (View b : plusButtons) b.setVisibility(show ? View.VISIBLE : View.GONE);
                buildingLevelButton.setVisibility(show && selectedBuilding() != 0 ? View.VISIBLE : View.GONE);
                logButtonSpot.setVisibility(homeReady() && isAttackMenuOpen() && openPopupCount() == 1 ? View.VISIBLE : View.GONE);
                if (speedButton != null) speedButton.setVisibility(isBattleActive() && !homeReady() && !isReplayActive() ? View.VISIBLE : View.GONE);
                if (replayExitButton != null) replayExitButton.setVisibility(isReplayActive() ? View.VISIBLE : View.GONE);
                // The social popup's profile page (page 6; settings is 26) shows the sandbox profile, any other page hides it.
                int tab = socialTab();
                boolean showProfile = tab == PROFILE_PAGE;
                if (showProfile && profilePanel.getVisibility() != View.VISIBLE) { fillProfile(activity, profilePanel); profilePanel.setVisibility(View.VISIBLE); }
                else if (!showProfile && profilePanel.getVisibility() == View.VISIBLE) profilePanel.setVisibility(View.GONE);
                new Handler(Looper.getMainLooper()).postDelayed(this, 300);
            }
        });
    }



    /** Green rounded "+" drawn like the HUD gems button (gradient square, dark rim, white cross with dark outline); drawn=false keeps it transparent over the game's own "+". */
    private static View plusButton(Activity a, FrameLayout root, int[] r, boolean drawn) {
        View b = new View(a) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas c) {
                if (!drawn) return;
                float w = getWidth(), h = getHeight(), rad = w * 0.2f;
                p.setShader(null); p.setColor(0xFF1E3F0C); c.drawRoundRect(0, 0, w, h, rad, rad, p);
                p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF9BE04F, 0xFF4E9A1F, android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(w * 0.06f, h * 0.06f, w * 0.94f, h * 0.94f, rad * 0.8f, rad * 0.8f, p);
                p.setShader(null);
                float t = w * 0.13f, len = w * 0.30f, cx = w / 2, cy = h / 2, o = w * 0.05f;
                p.setColor(0xFF1E3F0C);
                c.drawRoundRect(cx - t - o, cy - len - o, cx + t + o, cy + len + o, t, t, p);
                c.drawRoundRect(cx - len - o, cy - t - o, cx + len + o, cy + t + o, t, t, p);
                p.setColor(Color.WHITE);
                c.drawRoundRect(cx - t, cy - len, cx + t, cy + len, t * 0.7f, t * 0.7f, p);
                c.drawRoundRect(cx - len, cy - t, cx + len, cy + t, t * 0.7f, t * 0.7f, p);
            }
        };
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(r[2], r[2]);
        lp.leftMargin = r[0]; lp.topMargin = r[1];
        root.addView(b, lp);
        b.setVisibility(View.GONE);
        plusButtons.add(b);
        return b;
    }

    private static boolean homeReady() { return com.supercell.titan.GameApp.localHomeState() == 2 && !isBattleActive(); }

    // ---- game-styled dialogs (Supercell-Magic font, brown frame, beige panel, green buttons) ----

    private static android.graphics.Typeface font(Activity a) {
        try { return android.graphics.Typeface.createFromAsset(a.getAssets(), "font/Supercell-Magic_5.ttf"); } catch (Exception e) { return android.graphics.Typeface.DEFAULT_BOLD; }
    }

    private static android.widget.TextView label(Activity a, String text, float size, int color, boolean outline) {
        android.widget.TextView t = new android.widget.TextView(a);
        t.setText(text); t.setTextSize(size); t.setTextColor(color); t.setTypeface(font(a)); t.setGravity(Gravity.CENTER);
        if (outline) t.setShadowLayer(4, 0, 3, Color.BLACK);
        return t;
    }

    private static android.graphics.drawable.GradientDrawable box(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill); g.setCornerRadius(radius); g.setStroke(6, stroke);
        return g;
    }

    /** Game-style popup: outlined title, optional message, one game button per item (two columns when there are many). */
    private static void cocDialog(Activity a, String title, String message, String[] items, IntChoice choice) { cocDialog(a, title, message, items, choice, false); }

    /** keepOpen: a menu step that stays below what it opens (closed by the player); otherwise the choice closes it. */
    private static void cocDialog(Activity a, String title, String message, String[] items, IntChoice choice, boolean keepOpen) {
        int H = SandboxShop.screenH(a);
        android.widget.LinearLayout body = new android.widget.LinearLayout(a);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        if (message != null && !message.isEmpty()) {
            View m = SandboxShop.text(a, message, H * 0.032f, 0xFF3A3A33);
            android.widget.LinearLayout.LayoutParams ml = new android.widget.LinearLayout.LayoutParams(-1, -2); ml.bottomMargin = Math.round(H * 0.02f);
            body.addView(m, ml);
        }
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        int columns = items.length > 4 ? 2 : 1, buttonH = Math.round(H * 0.105f), gap = Math.round(H * 0.016f);
        android.widget.LinearLayout grid = new android.widget.LinearLayout(a); grid.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int first = 0; first < items.length; first += columns) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(a);
            for (int column = 0; column < columns; column++) {
                int index = first + column;
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, buttonH, 1);
                if (column > 0) lp.leftMargin = gap;
                if (index >= items.length) { row.addView(new View(a), lp); continue; }
                View b = SandboxShop.button(a, items[index], items.length == 1 ? SandboxShop.GREEN : SandboxShop.BLUE, H * 0.034f);
                b.setOnClickListener(v -> { if (!keepOpen) dialog[0].dismiss(); choice.chosen(index); });
                row.addView(b, lp);
            }
            android.widget.LinearLayout.LayoutParams rl = new android.widget.LinearLayout.LayoutParams(-1, -2); if (first > 0) rl.topMargin = gap;
            grid.addView(row, rl);
        }
        View list = grid;
        if (items.length > 10) {
            android.widget.ScrollView scroll = new android.widget.ScrollView(a); scroll.addView(grid);
            list = scroll; list.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.55f)));
        }
        body.addView(list);
        dialog[0] = SandboxShop.popup(a, title, body, columns == 2 ? 0.56f : 0.42f);
        dialog[0].show();
    }

    private interface IntChoice { void chosen(int value); }
    private interface Edit { String apply() throws Exception; }

    /** The sandbox tools, laid out like the game's shop (tabs of item cards). */
    private static void showDashboard(Activity a) {
        SandboxShop.Item resources = new SandboxShop.Item("Ресурсы", "Золото, эликсир, тёмный эликсир и кристаллы", "Пополнить", "res", () ->
            gridDialog(a, "Пополнить ресурсы", new String[]{"Золото", "Эликсир", "Тёмный эликсир", "Кристаллы"}, resource ->
                confirm(a, resource == 3 ? "Установить 100 000 кристаллов?" : "Заполнить хранилище?", () -> {
                    if (resource == 3) { setLiveGems(100000); Toast.makeText(a, "Кристаллы: 100 000", Toast.LENGTH_SHORT).show(); return; }
                    // Gold / elixir / dark elixir: set in the running game (no reload); the cap comes from the save.
                    try { setLiveResource(resource + 1, (int) Math.min(Integer.MAX_VALUE, storageCap(a, 3000001 + resource))); Toast.makeText(a, "Хранилище заполнено", Toast.LENGTH_SHORT).show(); }
                    catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                })));
        SandboxShop.Item buildings = new SandboxShop.Item("Здания", "Уровень любого здания, всё на максимум, суперзаряд", "Изменить", "buildings", () ->
            gridDialog(a, "Здания", new String[]{"Выбрать", "Максимальный уровень", "Суперзаряд на максимум"}, action -> {
                if (action == 0) chooseBuildingList(a);
                else if (action == 1) confirm(a, "Поднять все здания до максимального уровня? Строящиеся здания пропускаются.", () -> runEdit(a, () -> setAllBuildingsMax(a)));
                else confirm(a, "Все фазы суперзаряда для зданий максимального уровня?", () -> runEdit(a, () -> setAllSupercharge(a)));
            }));
        SandboxShop.Item equipment = new SandboxShop.Item("Всё на максимум", "Все герои и всё снаряжение до максимального уровня", "Поднять", "equip", () ->
            confirm(a, "Поднять всех героев и всё снаряжение до максимального уровня?", () -> runEdit(a, () -> {
                JSONArray catalog = unitCatalog(a); int heroes = 0;
                List<Op> ops = parseOps(read(new File(a.getFilesDir(), "save-avatar.bin")));
                int at = findLevelList(ops, unitLimits(catalog, "heroes"));
                java.util.Set<Long> unlocked = new java.util.HashSet<>();
                for (int k = 0; k < ops.get(at).value; k++) unlocked.add(ops.get(at + 1 + 2 * k).value);
                for (int i = 0; i < catalog.length(); i++) {
                    JSONObject item = catalog.getJSONObject(i);
                    if ("heroes".equals(item.getString("group")) && unlocked.contains(item.getLong("id"))) { writeUnitLevel(a, item, item.getInt("max")); heroes++; }
                }
                return "Героев: " + heroes + ". " + setAllEquipmentMax(a);
            })));
        String modifier = MODIFIERS[Math.max(0, Math.min(MODIFIERS.length - 1, savedModifier(a)))];
        SandboxShop.Tab[] tabs = {
            new SandboxShop.Tab("Деревня", "th", "res", "th",
                resources, buildings,
                new SandboxShop.Item("Стражи", "Уровни стражей деревни", "Изменить", "guard", () -> maxMenu(a, "Стражи", () -> chooseGuardian(a), "Поднять всех стражей до максимального уровня?", () -> setAllGuardiansMax(a))),
                new SandboxShop.Item("Ратуша", "Готовая деревня под ратушу 13–18", "Выбрать", "th", () -> chooseTownHallProfile(a)),
                new SandboxShop.Item("Импорт прокачки", "Уровни из экспорта аккаунта оригинальной игры", "Вставить", "shots", () -> importProgress(a))),
            new SandboxShop.Tab("Армия", "troops", "queen", "troops",
                new SandboxShop.Item("Войска", "Уровни войск", "Изменить", "troops", () -> unitMenu(a, "troops", "Уровни войск", "войска")),
                new SandboxShop.Item("Заклинания", "Уровни заклинаний", "Изменить", "spells", () -> unitMenu(a, "spells", "Уровни заклинаний", "заклинания")),
                new SandboxShop.Item("Осадные машины", "Уровни осадных машин", "Изменить", "siege", () -> unitMenu(a, "sieges", "Осадные машины", "осадные машины")),
                new SandboxShop.Item("Зал героев", "Уровни героев и их снаряжения", "Открыть", "queen", () -> heroWindow(a)),
                new SandboxShop.Item("Питомцы", "Уровни питомцев", "Изменить", "pets", () -> unitMenu(a, "pets", "Уровни питомцев", "питомцев")),
                equipment),
            new SandboxShop.Tab("Планировки", "layout", "layout", "shots",
                new SandboxShop.Item("Редактор", "Расставить базу в любом из 9 слотов", "Открыть", "layout", () -> { SandboxShop.closeAll(); requestLayoutEditor(); }),
                new SandboxShop.Item("База по скриншотам", "Распознать базу с фото и сохранить в слот", "Выбрать", "shots", () -> chooseScreenshots(a))),
            new SandboxShop.Tab("Бой", "replays", "army", "modifier",
                new SandboxShop.Item("Армия по ссылке", "Вставить ссылку на армию из буфера обмена", "Вставить", "army", () -> armyFromClipboard(a)),
                new SandboxShop.Item("Модификатор", "Сейчас: " + modifier, "Изменить", "modifier", () -> chooseModifier(a)),
                new SandboxShop.Item("Крепость в защите", "Войска, которые выходят из крепости клана", "Выбрать", "guard", () -> defenseCastleWindow(a)),
                new SandboxShop.Item("Повторы", "Записи ваших атак", "Смотреть", "replays", () -> showReplays(a)))
        };
        SandboxShop.show(a, tabs, "Офлайн-песочница");
    }

    private static void gridDialog(Activity a, String title, String[] items, IntChoice choice) {
        cocDialog(a, title, null, items, choice, true);
    }

    private static JSONArray buildingGuardianCatalog(Activity a) throws Exception {
        try (java.io.InputStream in = a.getAssets().open("building-guardian-levels.json")) {
            ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) data.write(buffer, 0, n);
            return new JSONArray(new String(data.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static JSONObject catalogItem(JSONArray catalog, String group, long id) throws Exception {
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.getJSONObject(i);
            if (item.getLong("id") == id && group.equals(item.getString("group"))) return item;
        }
        throw new IllegalArgumentException("Уровни этого объекта пока недоступны");
    }

    private static JSONObject findBuilding(JSONObject home, long instanceId, long dataId) throws Exception {
        JSONObject found = null;
        // Instance IDs overlap between villages: match both the instance and type.
        for (String key : new String[]{"buildings", "buildings2"}) {
            JSONArray buildings = home.optJSONArray(key);
            for (int i = 0; buildings != null && i < buildings.length(); i++) {
                JSONObject building = buildings.getJSONObject(i);
                if (building.optLong("id", -1) == instanceId && building.optLong("data", -1) == dataId) {
                    if (found != null) throw new IllegalArgumentException("Неоднозначное здание в сохранении");
                    found = building;
                }
            }
        }
        if (found == null) throw new IllegalArgumentException("Здание не найдено: выберите его повторно");
        return found;
    }

    private static void setBuildingLevel(JSONObject home, long instanceId, long dataId, int level, int max) throws Exception {
        if (level < 1 || level > max) throw new IllegalArgumentException("Недопустимый уровень здания");
        JSONObject building = findBuilding(home, instanceId, dataId);
        if (building.optInt("const_t", 0) > 0) throw new IllegalArgumentException("Сначала завершите строительство в игре");
        // Keep type, merged identity, instance ID and every layout position intact.
        building.put("lvl", level - 1);
    }

    private static void chooseSelectedBuilding(Activity a, long selection) {
        if (selection == 0) { Toast.makeText(a, "Сначала выберите здание", Toast.LENGTH_SHORT).show(); return; }
        long instanceId = selection & 0xffffffffL, dataId = selection >>> 32;
        try {
            JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
            JSONObject building = findBuilding(home, instanceId, dataId);
            JSONObject item = catalogItem(buildingGuardianCatalog(a), "buildings", dataId);
            if (item.optBoolean("merged", false)) item.put("note", "Объединённое здание: уровень 1 сохраняет объединение. Тип и размещение не меняются.");
            int max = item.getInt("max"); String name = item.getString("name");
            showLevelPicker(a, item, building.optInt("lvl", 0) + 1, level -> {
                // In the running game (LogicBuilding::setUpgradeLevel); the autosave stores it. Under construction: finish it in game first.
                if (level < 1 || level > max) { Toast.makeText(a, "Недопустимый уровень здания", Toast.LENGTH_SHORT).show(); return; }
                if (building.optInt("const_t", 0) > 0) { Toast.makeText(a, "Сначала завершите строительство в игре", Toast.LENGTH_LONG).show(); return; }
                setLiveBuildingLevel((int) instanceId, level - 1);
                Toast.makeText(a, name + ": уровень " + level, Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** Building levels by type: one tile per building type (count, lowest level); the chosen level is set on every building of that type in the running game. */
    private static void chooseBuildingList(Activity a) {
        try {
            JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
            JSONArray catalog = buildingGuardianCatalog(a), buildings = home.optJSONArray("buildings");
            java.util.Map<Long, JSONObject> metadata = new java.util.HashMap<>();
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject item = catalog.getJSONObject(i);
                if ("buildings".equals(item.getString("group"))) metadata.put(item.getLong("id"), item);
            }
            java.util.Map<Long, List<JSONObject>> byType = new java.util.LinkedHashMap<>();
            for (int i = 0; buildings != null && i < buildings.length(); i++) {
                JSONObject building = buildings.getJSONObject(i); long dataId = building.getLong("data");
                if (!metadata.containsKey(dataId) || building.optInt("x", -1) < 0 || building.optInt("y", -1) < 0) continue;
                byType.computeIfAbsent(dataId, k -> new ArrayList<>()).add(building);
            }
            List<Long> types = new ArrayList<>(byType.keySet()); List<String> names = new ArrayList<>();
            long[] ids = new long[types.size()];
            for (int t = 0; t < types.size(); t++) {
                JSONObject item = metadata.get(types.get(t)); List<JSONObject> list = byType.get(types.get(t));
                int low = Integer.MAX_VALUE; for (JSONObject b : list) low = Math.min(low, b.optInt("lvl", 0) + 1);
                Integer applied = appliedLevels.get("buildings:" + types.get(t)); if (applied != null) low = applied;
                names.add(item.getString("name") + (list.size() > 1 ? " ×" + list.size() : "") + " · " + low + " / " + item.getInt("max"));
                ids[t] = types.get(t);
            }
            searchDialog(a, "Уровни зданий", names.toArray(new String[0]), ids, index -> {
                try {
                    JSONObject item = new JSONObject(metadata.get(types.get(index)).toString());
                    List<JSONObject> list = byType.get(types.get(index));
                    if (item.optBoolean("merged", false)) item.put("note", "Объединённое здание: уровень 1 сохраняет объединение.");
                    int low = Integer.MAX_VALUE; for (JSONObject b : list) low = Math.min(low, b.optInt("lvl", 0) + 1);
                    Integer applied = appliedLevels.get("buildings:" + types.get(index)); if (applied != null) low = applied;
                    showLevelPicker(a, item, low, level -> {
                        appliedLevels.put("buildings:" + types.get(index), level);
                        int changed = 0, building = 0;
                        for (JSONObject b : list) {
                            if (b.optInt("const_t", 0) > 0) { building++; continue; }  // under construction: finish it in game first
                            setLiveBuildingLevel(b.optInt("id"), level - 1); changed++;
                        }
                        Toast.makeText(a, item.optString("name") + ": уровень " + level + (list.size() > 1 ? " (" + changed + " шт.)" : "") + (building > 0 ? ", строятся: " + building : ""), Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
            }, () -> chooseBuildingList(a));
        } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** Category menu: pick one item, or raise the whole category to its maximum level. */
    private static void maxMenu(Activity a, String title, Runnable pick, String question, Edit allMax) {
        gridDialog(a, title, new String[]{"Выбрать", "Максимальный уровень"}, action -> {
            if (action == 0) pick.run();
            else confirm(a, question, () -> runEdit(a, allMax));
        });
    }

    private static void unitMenu(Activity a, String group, String title, String what) {
        maxMenu(a, title, () -> chooseUnit(a, group, title), "Поднять все " + what + " до максимального уровня?", () -> {
            JSONArray catalog = unitCatalog(a);
            List<Op> ops = parseOps(read(new File(a.getFilesDir(), "save-avatar.bin")));
            int at = findLevelList(ops, unitLimits(catalog, group));
            java.util.Set<Long> owned = new java.util.HashSet<>();
            for (int k = 0; k < ops.get(at).value; k++) owned.add(ops.get(at + 1 + 2 * k).value);
            int changed = 0;
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject item = catalog.getJSONObject(i);
                if (group.equals(item.getString("group")) && owned.contains(item.getLong("id"))) { writeUnitLevel(a, item, item.getInt("max")); changed++; }
            }
            return "На максимум: " + changed;
        });
    }

    private static String setAllBuildingsMax(Activity a) throws Exception {
        File file = new File(a.getFilesDir(), "save-home.json");
        JSONObject home = new JSONObject(new String(read(file), StandardCharsets.UTF_8));
        JSONArray catalog = buildingGuardianCatalog(a), buildings = home.optJSONArray("buildings");
        java.util.Map<Long, Integer> max = new java.util.HashMap<>();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.getJSONObject(i);
            if ("buildings".equals(item.getString("group"))) max.put(item.getLong("id"), item.getInt("max"));
        }
        int changed = 0, skipped = 0;
        for (int i = 0; buildings != null && i < buildings.length(); i++) {
            JSONObject b = buildings.getJSONObject(i); Integer m = max.get(b.getLong("data"));
            if (m == null) continue;
            if (b.optInt("const_t", 0) > 0) { skipped++; continue; }  // same rule as a single building: finish construction first
            if (b.optInt("lvl", 0) != m - 1) { b.put("lvl", m - 1); changed++; }
        }
        write(file, home.toString().getBytes(StandardCharsets.UTF_8));
        return "Зданий поднято: " + changed + (skipped > 0 ? ", строятся: " + skipped : "");
    }

    /** Supercharge ("minilvl" = completed phases) at its maximum for every max-level building that has phases (assets/mini-levels.json). */
    private static String setAllSupercharge(Activity a) throws Exception {
        File file = new File(a.getFilesDir(), "save-home.json");
        JSONObject home = new JSONObject(new String(read(file), StandardCharsets.UTF_8));
        JSONObject phases = new JSONObject(new String(asset(a, "mini-levels.json"), StandardCharsets.UTF_8));
        JSONArray catalog = buildingGuardianCatalog(a), buildings = home.optJSONArray("buildings");
        java.util.Map<Long, Integer> max = new java.util.HashMap<>();
        for (int i = 0; i < catalog.length(); i++) { JSONObject it = catalog.getJSONObject(i); if ("buildings".equals(it.getString("group"))) max.put(it.getLong("id"), it.getInt("max")); }
        int changed = 0, notMax = 0;
        for (int i = 0; buildings != null && i < buildings.length(); i++) {
            JSONObject b = buildings.getJSONObject(i); String id = b.getString("data");
            if (!phases.has(id)) continue;
            Integer m = max.get(b.getLong("data"));
            if (m == null || b.optInt("lvl", 0) != m - 1) { notMax++; continue; }
            if (b.optInt("minilvl", 0) != phases.getInt(id)) { b.put("minilvl", phases.getInt(id)); changed++; }
        }
        write(file, home.toString().getBytes(StandardCharsets.UTF_8));
        return "Суперзаряд: " + changed + " зданий" + (notMax > 0 ? ", не макс. уровня: " + notMax : "");
    }

    private static String setAllGuardiansMax(Activity a) throws Exception {
        File file = new File(a.getFilesDir(), "save-home.json");
        JSONObject home = new JSONObject(new String(read(file), StandardCharsets.UTF_8));
        JSONObject manager = home.optJSONObject("guardian_manager");
        JSONArray guardians = manager == null ? null : manager.optJSONArray("m_pGuardians");
        if (guardians == null || guardians.length() == 0) throw new IllegalArgumentException("Нет открытых стражей");
        JSONArray catalog = buildingGuardianCatalog(a);
        for (int i = 0; i < guardians.length(); i++) {
            JSONObject guardian = guardians.getJSONObject(i);
            guardian.put("m_pGuardianUpgradeLevel", catalogItem(catalog, "guardians", guardian.getLong("m_pGuardian")).getInt("max") - 1);
        }
        write(file, home.toString().getBytes(StandardCharsets.UTF_8));
        return "Стражей на максимум: " + guardians.length();
    }

    private static void setGuardianLevel(JSONObject home, long id, int level, int max) throws Exception {
        if (level < 1 || level > max) throw new IllegalArgumentException("Недопустимый уровень стража");
        JSONObject manager = home.optJSONObject("guardian_manager");
        JSONArray guardians = manager == null ? null : manager.optJSONArray("m_pGuardians");
        for (int i = 0; guardians != null && i < guardians.length(); i++) {
            JSONObject guardian = guardians.getJSONObject(i);
            if (guardian.optLong("m_pGuardian", -1) == id) {
                guardian.put("m_pGuardianUpgradeLevel", level - 1); return;
            }
        }
        throw new IllegalArgumentException("Этот страж ещё не открыт");
    }

    private static void chooseGuardian(Activity a) {
        try {
            JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
            JSONObject manager = home.optJSONObject("guardian_manager");
            JSONArray guardians = manager == null ? null : manager.optJSONArray("m_pGuardians");
            if (guardians == null || guardians.length() == 0) throw new IllegalArgumentException("Нет открытых стражей");
            JSONArray catalog = buildingGuardianCatalog(a);
            List<JSONObject> items = new ArrayList<>(); List<Integer> levels = new ArrayList<>(); List<String> names = new ArrayList<>();
            for (int i = 0; i < guardians.length(); i++) {
                JSONObject guardian = guardians.getJSONObject(i);
                JSONObject item = catalogItem(catalog, "guardians", guardian.getLong("m_pGuardian"));
                int level = guardian.optInt("m_pGuardianUpgradeLevel", 0) + 1;
                items.add(item); levels.add(level); names.add(item.getString("name") + " · " + level + " / " + item.getInt("max"));
            }
            long[] ids = new long[items.size()]; for (int i = 0; i < ids.length; i++) ids[i] = items.get(i).getLong("id");
            searchDialog(a, "Стражи ратуши", names.toArray(new String[0]), ids, index -> {
                JSONObject item = items.get(index);
                showLevelPicker(a, item, levels.get(index), level -> runEdit(a, () -> {
                    File file = new File(a.getFilesDir(), "save-home.json");
                    JSONObject latest = new JSONObject(new String(read(file), StandardCharsets.UTF_8));
                    setGuardianLevel(latest, item.getLong("id"), level, item.getInt("max"));
                    write(file, latest.toString().getBytes(StandardCharsets.UTF_8)); return "Уровень стража обновлён";
                }));
            });
        } catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private static JSONArray unitCatalog(Activity a) throws Exception {
        try (java.io.InputStream in = a.getAssets().open("unit-levels.json")) {
            ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) data.write(buffer, 0, n);
            JSONArray catalog = new JSONArray(new String(data.toByteArray(), StandardCharsets.UTF_8));
            // Siege machines keep their levels in a list of their own in the save (sieges_upgrades), apart from the troops.
            JSONArray sieges = new JSONArray(new String(asset(a, "siege-ids.json"), StandardCharsets.UTF_8));
            java.util.Set<Long> siegeIds = new java.util.HashSet<>();
            for (int i = 0; i < sieges.length(); i++) siegeIds.add(sieges.getLong(i));
            for (int i = 0; i < catalog.length(); i++) { JSONObject item = catalog.getJSONObject(i); if (siegeIds.contains(item.getLong("id"))) item.put("group", "sieges"); }
            return catalog;
        }
    }

    private static java.util.Map<Long, Integer> unitLimits(JSONArray catalog, String group) throws Exception {
        java.util.Map<Long, Integer> limits = new java.util.HashMap<>();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.getJSONObject(i);
            if (group.equals(item.getString("group"))) limits.put(item.getLong("id"), item.getInt("max"));
        }
        return limits;
    }

    /** Upgrade lists: u32 count, followed by (data ID, zero-based level) pairs.
     * Army-count and cosmetic lists share the stream format. Require known IDs,
     * valid levels, uniqueness and an unambiguous largest complete list. */
    private static int findLevelList(List<Op> ops, java.util.Map<Long, Integer> limits) {
        int best = -1, largest = 0; boolean tied = false;
        for (int i = 0; i < ops.size(); i++) {
            Op count = ops.get(i);
            if (count.method != 0x50 || count.value < 1 || count.value > limits.size() || i + 2 * count.value >= ops.size()) continue;
            java.util.Set<Long> ids = new java.util.HashSet<>(); boolean valid = true;
            for (int k = 0; k < count.value && valid; k++) {
                Op id = ops.get(i + 1 + 2 * k), level = ops.get(i + 2 + 2 * k);
                Integer max = limits.get(id.value);
                valid = id.method == 0x50 && level.method == 0x88 && max != null && level.value >= 0 && level.value < max && ids.add(id.value);
            }
            if (!valid) continue;
            if (count.value > largest) { best = i; largest = (int) count.value; tied = false; }
            else if (count.value == largest) tied = true;
        }
        if (best < 0 || tied) throw new IllegalArgumentException("Не удалось однозначно определить список уровней");
        return best;
    }

    private static void chooseUnit(Activity a, String group, String title) {
        try {
            JSONArray catalog = unitCatalog(a);
            List<Op> ops = parseOps(read(new File(a.getFilesDir(), "save-avatar.bin")));
            int at = findLevelList(ops, unitLimits(catalog, group));
            java.util.Map<Long, Long> levels = new java.util.HashMap<>();
            for (int k = 0; k < ops.get(at).value; k++) levels.put(ops.get(at + 1 + 2 * k).value, ops.get(at + 2 + 2 * k).value);
            for (java.util.Map.Entry<String, Integer> e : appliedLevels.entrySet())
                if (e.getKey().startsWith(group + ":")) levels.put(Long.parseLong(e.getKey().substring(group.length() + 1)), (long) e.getValue() - 1);
            List<JSONObject> items = new ArrayList<>(); List<String> names = new ArrayList<>();
            // Every home-village unit of the group is listed: one missing from the save's list has never been upgraded (level 1);
            // internal helper rows (no translated name) and builder base units (village 1) are left out.
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject item = catalog.getJSONObject(i); Long level = levels.get(item.getLong("id"));
                if (!group.equals(item.getString("group")) || "1".equals(item.optString("village"))) continue;
                if (level == null && !item.getString("name").matches(".*[А-Яа-яЁё].*")) continue;
                if (level == null) { level = 0L; levels.put(item.getLong("id"), 0L); }
                items.add(item); names.add(item.getString("name") + " · " + (level + 1) + " / " + item.getInt("max"));
            }
            long[] ids = new long[items.size()]; for (int i = 0; i < ids.length; i++) ids[i] = items.get(i).getLong("id");
            searchDialog(a, title, names.toArray(new String[0]), ids, selected -> {
                JSONObject item = items.get(selected);
                try { showLevelPicker(a, item, levels.get(item.getLong("id")) + 1, level -> liveUnitLevel(a, item, level)); }
                catch (Exception e) { Toast.makeText(a, e.getMessage(), Toast.LENGTH_LONG).show(); }
            }, () -> chooseUnit(a, group, title));
        } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** Unit level in the running game (no reload); heroes keep the reload path, their level is also stored on the hero altar. */
    private static void liveUnitLevel(Activity a, JSONObject item, int level) {
        try {
            if (level < 1 || level > item.getInt("max")) throw new IllegalArgumentException("Недопустимый уровень");
            if ("heroes".equals(item.getString("group"))) {
                // The hero's slot in the hero hall (saved "heroN" / "heroLevelN") is updated in the running game too.
                JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
                JSONArray buildings = home.optJSONArray("buildings"); boolean done = false;
                for (int i = 0; buildings != null && i < buildings.length() && !done; i++) {
                    JSONObject b = buildings.getJSONObject(i);
                    for (int h = 0; h < b.optInt("numHeroes", 0); h++) {
                        JSONObject hero = b.optJSONObject("hero" + h);
                        if (hero != null && hero.optLong("hero_global_id", -1) == item.getLong("id")) { setLiveHeroLevel(b.getInt("id"), h, (int) item.getLong("id"), level - 1); done = true; break; }
                    }
                }
                if (!done) { runEdit(a, () -> writeUnitLevel(a, item, level)); return; }
                appliedLevels.put("heroes:" + item.getLong("id"), level);
                Toast.makeText(a, item.getString("name") + ": уровень " + level, Toast.LENGTH_SHORT).show();
                return;
            }
            setLiveUnitLevel((int) item.getLong("id"), level - 1);
            appliedLevels.put(item.getString("group") + ":" + item.getLong("id"), level);
            Toast.makeText(a, item.getString("name") + ": уровень " + level, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private static String writeUnitLevel(Activity a, JSONObject item, int level) throws Exception {
        if (level < 1 || level > item.getInt("max")) throw new IllegalArgumentException("Недопустимый уровень");
        File file = new File(a.getFilesDir(), "save-avatar.bin"); byte[] data = read(file); List<Op> ops = parseOps(data);
        int at = findLevelList(ops, unitLimits(unitCatalog(a), item.getString("group")));
        for (int k = 0; k < ops.get(at).value; k++) {
            if (ops.get(at + 1 + 2 * k).value != item.getLong("id")) continue;
            byte[] previous = data.clone();
            setValue(data, ops.get(at + 2 + 2 * k), level - 1);
            parseOps(data);
            byte[] heroHome = null;
            File homeFile = new File(a.getFilesDir(), "save-home.json");
            if ("heroes".equals(item.getString("group"))) {
                JSONObject home = new JSONObject(new String(read(homeFile), StandardCharsets.UTF_8));
                for (String key : new String[]{"buildings", "buildings2"}) {
                    JSONArray buildings = home.optJSONArray(key);
                    for (int i = 0; buildings != null && i < buildings.length(); i++) {
                        JSONObject building = buildings.getJSONObject(i);
                        for (int hero = 0; hero < building.optInt("numHeroes", 0); hero++) {
                            JSONObject heroId = building.optJSONObject("hero" + hero);
                            if (heroId != null && heroId.optLong("hero_global_id", -1) == item.getLong("id"))
                                building.put("heroLevel" + hero, new JSONObject().put("hero_level", level - 1));
                        }
                    }
                }
                heroHome = home.toString().getBytes(StandardCharsets.UTF_8);
            }
            write(file, data);
            if (heroHome != null) {
                try { write(homeFile, heroHome); }
                catch (Exception failure) {
                    try { write(file, previous); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            }
            return item.getString("name") + ": уровень " + level;
        }
        throw new IllegalArgumentException("Боец не найден в сохранении");
    }

    private static void searchDialog(Activity a, String title, String[] names, IntChoice choice) { searchDialog(a, title, names, null, choice); }

    // The list window under an open level picker and how to rebuild it: after a level is applied the list is reopened with fresh levels.
    private static android.app.Dialog currentList;
    private static Runnable currentListReopen;
    // Levels applied in the running game (group:id -> level from 1) until the autosave writes them; the lists read the save.
    private static final java.util.Map<String, Integer> appliedLevels = new java.util.HashMap<>();
    private static void searchDialog(Activity a, String title, String[] names, long[] ids, IntChoice choice, Runnable reopen) {
        searchDialog(a, title, names, ids, choice);
        currentListReopen = reopen;
    }

    /** Item picker: a grid of tiles ("Name · level / max · extra" -> name and a level badge, the game's icon of ids[i]) with a search field. */
    private static void searchDialog(Activity a, String title, String[] names, long[] ids, IntChoice choice) {
        int H = SandboxShop.screenH(a), W = SandboxShop.screenW(a);
        android.widget.LinearLayout body = new android.widget.LinearLayout(a); body.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.EditText search = new android.widget.EditText(a);
        search.setSingleLine(true); search.setHint("Поиск"); search.setTypeface(font(a)); search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, H * 0.03f);
        search.setTextColor(0xFF3A3A33); search.setHintTextColor(0xFF9A9A90); search.setBackground(box(0xFFFFFFFF, 0xFFBDBDB3, 18));
        search.setPadding(Math.round(H * 0.025f), Math.round(H * 0.012f), Math.round(H * 0.025f), Math.round(H * 0.012f));
        body.addView(search, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        android.widget.LinearLayout grid = new android.widget.LinearLayout(a); grid.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(grid);
        android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.56f)); sl.topMargin = Math.round(H * 0.018f);
        body.addView(scroll, sl);
        float width = 0.74f; int columns = 5, gap = Math.round(H * 0.014f);
        int tileW = (Math.round(W * width) - Math.round(H * 0.084f) - (columns - 1) * gap) / columns, tileH = Math.round(tileW * 1.05f);
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            grid.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            android.widget.LinearLayout row = null; int inRow = 0;
            for (int i = 0; i < names.length; i++) {
                String[] parts = names[i].split(" · ");
                if (!parts[0].toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                String level = parts.length > 1 ? parts[1].replace(" ", "") : null;
                String[] lm = level == null ? new String[0] : level.split("/");
                boolean max = lm.length == 2 && lm[0].equals(lm[1]);
                if (row == null || inRow == columns) {
                    row = new android.widget.LinearLayout(a); inRow = 0;
                    android.widget.LinearLayout.LayoutParams rl = new android.widget.LinearLayout.LayoutParams(-1, tileH); if (grid.getChildCount() > 0) rl.topMargin = gap;
                    grid.addView(row, rl);
                }
                final int index = i;
                View t = SandboxShop.tile(a, parts[0], max ? "МАКС" : level, max, ids == null ? null : SandboxShop.icon(a, ids[i]), tileW, tileH);
                t.setOnClickListener(v -> choice.chosen(index));  // the list stays below the level picker
                android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(tileW, tileH); if (inRow > 0) tl.leftMargin = gap;
                row.addView(t, tl); inRow++;
            }
        };
        fill[0].run();
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence q, int start, int count, int after) {}
            public void onTextChanged(CharSequence q, int start, int before, int count) { fill[0].run(); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        dialog[0] = SandboxShop.popup(a, title, body, width);
        dialog[0].show();
        currentList = dialog[0]; currentListReopen = null;
    }

    private static void confirm(Activity a, String text, Runnable yes) {
        cocDialog(a, "Подтверждение", text, new String[]{"Да"}, w -> yes.run());
    }

    /** Ask native code to flush and pause autosave, wait for it, apply the edit, then relaunch (or resume on failure). */
    private static void runEdit(Activity a, Edit edit) {
        SandboxShop.closeAll();
        appliedLevels.clear();  // the save is flushed before the edit, so it holds every applied level
        requestSaveAndPause();
        Handler handler = new Handler(Looper.getMainLooper());
        long deadline = System.currentTimeMillis() + 5000;
        handler.post(new Runnable() {
            @Override public void run() {
                if (!isSaveAndPauseDone()) {
                    if (System.currentTimeMillis() < deadline) { handler.postDelayed(this, 100); return; }
                    Toast.makeText(a, "Игра не готова к правке сохранения (идёт бой?)", Toast.LENGTH_LONG).show();
                    return;
                }
                String result;
                boolean relaunch;
                try { result = edit.apply(); relaunch = true; }
                catch (Exception e) { result = "Ошибка: " + e.getMessage(); relaunch = true; }
                Toast.makeText(a, result, Toast.LENGTH_SHORT).show();
                // The edited saves are loaded in place (native go-home path); native code falls back to a relaunch if that fails.
                if (relaunch) requestReloadHome();
            }
        });
    }


    // ---- layout import (recognised from screenshots): files/import-layout.json {"slot": N, "objects": [{"data": id, "x": x, "y": y}, ...]} ----
    // The player's own buildings of each type take the recognised positions in layout slot N ("lNx"); buildings without a position stay
    // in storage, traps and decorations are left out of the slot. Levels are untouched.
    private static int importCheck;
    private static volatile boolean importRunning;
    private static void checkLayoutImport(Activity a) {
        if (importRunning || ++importCheck % 45 != 0 || isBattleActive()) return;
        File f = new File(a.getFilesDir(), "import-layout.json");
        if (!f.exists()) return;
        importRunning = true;
        a.runOnUiThread(() -> runEdit(a, () -> {
            try { return importLayout(a, f); } finally { f.delete(); importRunning = false; }
        }));
    }

    private static String importLayout(Activity a, File source) throws Exception {
        JSONObject spec = new JSONObject(new String(read(source), StandardCharsets.UTF_8));
        int slot = spec.getInt("slot");
        if (slot <= 0 || slot > 13) throw new Exception("слот " + slot + " нельзя перезаписать");
        String key = "l" + slot + "x";
        File saveFile = new File(a.getFilesDir(), "save-home.json");
        JSONObject home = new JSONObject(new String(read(saveFile), StandardCharsets.UTF_8));
        // Objects come in confidence order; one that leaves the building area (tiles 3..46) or overlaps an object already placed is skipped,
        // so the slot is always a valid layout the editor can open and save.
        boolean[][] used = new boolean[50][50];
        java.util.Map<Long, java.util.ArrayDeque<JSONObject>> owned = new java.util.HashMap<>();
        JSONArray buildings = home.getJSONArray("buildings");
        for (int i = 0; i < buildings.length(); i++) {
            JSONObject b = buildings.getJSONObject(i); b.remove(key);
            owned.computeIfAbsent(b.getLong("data"), k -> new java.util.ArrayDeque<>()).add(b);
        }
        int placed = 0, unused = 0, rejected = 0;
        JSONArray objects = spec.getJSONArray("objects");
        for (int i = 0; i < objects.length(); i++) {
            JSONObject o = objects.getJSONObject(i);
            java.util.ArrayDeque<JSONObject> q = owned.get(o.getLong("data"));
            if (q == null || q.isEmpty()) { unused++; continue; }
            int x = o.getInt("x"), y = o.getInt("y"), w = o.optInt("w", 1);
            if (!occupy(used, x, y, w)) { rejected++; continue; }
            q.poll().put(key, new JSONArray().put(x).put(y)); placed++;
        }
        // A pending editor draft of this slot would hide the imported layout behind "your last change was restored".
        JSONArray state = home.optJSONArray("layout_state");
        if (state != null && slot < state.length()) state.put(slot, 0);
        for (String arr : LAYOUT_ARRAYS) {
            JSONArray items = home.optJSONArray(arr);
            for (int i = 0; items != null && i < items.length(); i++) items.getJSONObject(i).remove("e" + slot + "x");
        }
        JSONArray decos = home.optJSONArray("decos");
        for (int i = 0; decos != null && i < decos.length(); i++) decos.getJSONObject(i).remove(key);
        // Hero flags: each free flag takes the next recognised hero that fits (position "lNx", defending hero m_defendingHero_N);
        // the other flags leave the slot and keep a hero that is not used by a placed flag.
        JSONArray heroes = spec.optJSONArray("heroes"), flags = home.optJSONArray("heroFlags");
        int heroesPlaced = 0;
        if (flags != null) {
            List<long[]> fitting = new ArrayList<>();
            for (int i = 0; heroes != null && i < heroes.length() && fitting.size() < flags.length(); i++) {
                JSONObject h = heroes.getJSONObject(i);
                if (occupy(used, h.getInt("x"), h.getInt("y"), 2)) fitting.add(new long[]{h.getLong("hero"), h.getInt("x"), h.getInt("y")});
                else rejected++;
            }
            List<Long> spare = new ArrayList<>(java.util.Arrays.asList(28000000L, 28000001L, 28000002L, 28000004L, 28000006L, 28000007L));
            for (long[] h : fitting) spare.remove(h[0]);
            for (int i = 0; i < flags.length(); i++) {
                JSONObject f = flags.getJSONObject(i);
                long hero;
                if (i < fitting.size()) { long[] h = fitting.get(i); hero = h[0]; f.put(key, new JSONArray().put(h[1]).put(h[2])); heroesPlaced++; }
                else { f.remove(key); hero = spare.isEmpty() ? 28000000L : spare.remove(0); }
                f.put("m_defendingHero_" + slot, hero); f.put("m_defendingHeroDraft_" + slot, hero);
            }
        }
        // Traps and hidden teslas are invisible on battle screenshots: they go to random free tiles inside the recognised base
        // (bounding box of the placed objects, whole building area as fallback). Builder-base traps stay out of the slot.
        java.util.Random random = new java.util.Random();
        int minX = 47, minY = 47, maxX = 2, maxY = 2;
        for (int x = 3; x < 47; x++) for (int y = 3; y < 47; y++) if (used[x][y]) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        JSONObject trapSizes = new JSONObject(new String(asset(a, "trap-sizes.json"), StandardCharsets.UTF_8));
        int traps = 0;
        List<JSONObject> hidden = new ArrayList<>();
        java.util.ArrayDeque<JSONObject> teslas = owned.get(1000019L);
        while (teslas != null && !teslas.isEmpty()) hidden.add(teslas.poll());
        JSONArray trapItems = home.optJSONArray("traps");
        for (int i = 0; trapItems != null && i < trapItems.length(); i++) { JSONObject t = trapItems.getJSONObject(i); t.remove(key); if (trapSizes.has(t.getString("data"))) hidden.add(t); }
        java.util.Collections.shuffle(hidden, random);
        for (JSONObject o : hidden) {
            int w = o.getLong("data") == 1000019L ? 2 : trapSizes.getInt(o.getString("data"));
            int[] spot = randomFreeSpot(used, random, w, minX, minY, maxX, maxY);
            if (spot == null) spot = randomFreeSpot(used, random, w, 3, 3, 46, 46);
            if (spot == null) continue;
            o.put(key, new JSONArray().put(spot[0]).put(spot[1]));
            if (o.getLong("data") == 1000019L) placed++; else traps++;
        }
        int stored = buildings.length() - placed;
        write(saveFile, home.toString().getBytes(StandardCharsets.UTF_8));
        return "Планировка " + slot + ": расставлено " + placed + (heroesPlaced > 0 ? ", героев " + heroesPlaced : "") + (traps > 0 ? ", ловушек " + traps : "") + ", на складе " + stored
            + (unused > 0 ? ", лишних с картинки " + unused : "") + (rejected > 0 ? ", пропущено (за краем или внахлёст) " + rejected : "");
    }

    /** Random top-left tile (x, y in [x0, x1-w+1] x [y0, y1-w+1]) whose w x w footprint is free; marks it used. Null after 400 misses. */
    private static int[] randomFreeSpot(boolean[][] used, java.util.Random random, int w, int x0, int y0, int x1, int y1) {
        if (x1 - w + 1 < x0 || y1 - w + 1 < y0) return null;
        for (int attempt = 0; attempt < 400; attempt++) {
            int x = x0 + random.nextInt(x1 - w + 2 - x0), y = y0 + random.nextInt(y1 - w + 2 - y0);
            if (occupy(used, x, y, w)) return new int[]{x, y};
        }
        return null;
    }

    private static byte[] asset(Activity a, String name) throws Exception {
        try (java.io.InputStream in = a.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /** Marks the w x w footprint at (x, y) as used; false (nothing marked) if it leaves the building area or touches a used tile. */
    private static boolean occupy(boolean[][] used, int x, int y, int w) {
        if (x < 3 || y < 3 || x + w > 47 || y + w > 47) return false;
        for (int i = x; i < x + w; i++) for (int j = y; j < y + w; j++) if (used[i][j]) return false;
        for (int i = x; i < x + w; i++) for (int j = y; j < y + w; j++) used[i][j] = true;
        return true;
    }

    // ---- layout from screenshots (on-device LayoutRecognizer): gallery picker inside the game window ----
    // The system picker would send the game to the background, which relaunches the sandbox (RenderActivity.onRestart), so the latest
    // landscape images of the gallery are listed here. Tapping numbers them in screen order (top, [centre], bottom).

    private static void chooseScreenshots(Activity a) {
        String permission = android.os.Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES" : "android.permission.READ_EXTERNAL_STORAGE";
        if (a.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) { showScreenshotGrid(a); return; }
        a.requestPermissions(new String[]{permission}, 41);
        Handler h = new Handler(Looper.getMainLooper()); long deadline = System.currentTimeMillis() + 60000;
        h.postDelayed(new Runnable() {  // the permission dialog only pauses the activity; open the grid once access is granted
            @Override public void run() {
                if (a.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) showScreenshotGrid(a);
                else if (System.currentTimeMillis() < deadline) h.postDelayed(this, 500);
            }
        }, 500);
    }

    private static void showScreenshotGrid(Activity a) {
        List<android.net.Uri> uris = new ArrayList<>();
        String[] cols = {android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.WIDTH, android.provider.MediaStore.Images.Media.HEIGHT};
        try (android.database.Cursor c = a.getContentResolver().query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && uris.size() < 48)
                if (c.getInt(1) > c.getInt(2))  // battle screenshots are landscape
                    uris.add(android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
        } catch (Exception e) { Toast.makeText(a, "Галерея недоступна: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        if (uris.isEmpty()) { Toast.makeText(a, "В галерее нет горизонтальных скриншотов", Toast.LENGTH_LONG).show(); return; }
        android.app.Dialog dialog = new android.app.Dialog(a);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.LinearLayout frame = new android.widget.LinearLayout(a);
        frame.setOrientation(android.widget.LinearLayout.VERTICAL); frame.setPadding(24, 16, 24, 24);
        frame.setBackground(box(0xFFF1E3C4, 0xFF6B4A2B, 24));
        FrameLayout header = new FrameLayout(a);
        header.addView(label(a, "Отметьте скриншоты сверху вниз (2 или 3)", 14, 0xFF3A2A16, false), new FrameLayout.LayoutParams(-1, 100));
        android.widget.TextView close = label(a, "X", 15, Color.WHITE, true);
        close.setBackground(box(0xFFD8352A, 0xFF5A0E08, 12)); close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new FrameLayout.LayoutParams(90, 90, Gravity.END)); frame.addView(header);
        List<android.net.Uri> picked = new ArrayList<>(); List<android.widget.TextView> badges = new ArrayList<>();
        android.widget.LinearLayout rows = new android.widget.LinearLayout(a); rows.setOrientation(android.widget.LinearLayout.VERTICAL);
        int columns = 4, thumbW = (int) (a.getResources().getDisplayMetrics().widthPixels * .70 / columns) - 24;
        android.widget.LinearLayout row = null;
        for (int i = 0; i < uris.size(); i++) {
            if (i % columns == 0) { row = new android.widget.LinearLayout(a); rows.addView(row); }
            android.net.Uri uri = uris.get(i);
            FrameLayout cell = new FrameLayout(a);
            android.widget.ImageView image = new android.widget.ImageView(a); image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            cell.addView(image, new FrameLayout.LayoutParams(-1, -1));
            android.widget.TextView badge = label(a, "", 22, Color.WHITE, true); badge.setVisibility(View.GONE);
            badge.setBackground(box(0x9944A0E0, 0xFF203E58, 12));
            cell.addView(badge, new FrameLayout.LayoutParams(-1, -1)); badges.add(badge);
            final int index = i;
            cell.setOnClickListener(v -> {
                int at = picked.indexOf(uri);
                if (at >= 0) picked.remove(at); else if (picked.size() < 3) picked.add(uri);
                for (int j = 0; j < uris.size(); j++) {
                    int n = picked.indexOf(uris.get(j));
                    badges.get(j).setVisibility(n >= 0 ? View.VISIBLE : View.GONE); badges.get(j).setText(String.valueOf(n + 1));
                }
            });
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(thumbW, thumbW * 9 / 20); lp.setMargins(8, 8, 8, 8);
            row.addView(cell, lp);
            new Thread(() -> {  // thumbnails load off the UI thread
                try {
                    android.graphics.Bitmap t = android.os.Build.VERSION.SDK_INT >= 29 ? a.getContentResolver().loadThumbnail(uri, new android.util.Size(thumbW, thumbW / 2), null)
                        : android.provider.MediaStore.Images.Thumbnails.getThumbnail(a.getContentResolver(), android.content.ContentUris.parseId(uri), android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null);
                    a.runOnUiThread(() -> image.setImageBitmap(t));
                } catch (Exception ignored) {}
            }).start();
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(a); scroll.addView(rows);
        frame.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        android.widget.TextView go = label(a, "Распознать", 16, Color.WHITE, true);
        go.setPadding(24, 20, 24, 20); go.setBackground(box(0xFF7CC23B, 0xFF2F5E12, 22));
        go.setOnClickListener(v -> {
            if (picked.size() < 2) { Toast.makeText(a, "Отметьте хотя бы 2 скриншота: верх и низ базы", Toast.LENGTH_SHORT).show(); return; }
            dialog.dismiss(); recognizeScreenshots(a, new ArrayList<>(picked));
        });
        android.widget.LinearLayout.LayoutParams gp = new android.widget.LinearLayout.LayoutParams(-1, -2); gp.topMargin = 12;
        frame.addView(go, gp);
        dialog.setContentView(frame); dialog.show();
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * .70), (int) (a.getResources().getDisplayMetrics().heightPixels * .92));
        dialog.getWindow().getDecorView().setSystemUiVisibility(5894);
    }

    private static void recognizeScreenshots(Activity a, List<android.net.Uri> uris) {
        android.app.Dialog wait = new android.app.Dialog(a);
        wait.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); wait.setCancelable(false);
        android.widget.TextView text = label(a, "Загрузка нейросети…", 16, 0xFF3A2A16, false);
        text.setPadding(60, 50, 60, 50); text.setBackground(box(0xFFF1E3C4, 0xFF6B4A2B, 24));
        wait.setContentView(text); wait.show();
        wait.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        wait.getWindow().getDecorView().setSystemUiVisibility(5894);
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                LayoutRecognizer r = LayoutRecognizer.get(a);
                List<android.graphics.Bitmap> images = new ArrayList<>();
                for (android.net.Uri u : uris) try (java.io.InputStream in = a.getContentResolver().openInputStream(u)) { images.add(android.graphics.BitmapFactory.decodeStream(in)); }
                List<LayoutRecognizer.Obj> objs = r.recognize(images, step -> a.runOnUiThread(() -> text.setText(step)));
                long seconds = (System.currentTimeMillis() - t0) / 1000;
                a.runOnUiThread(() -> { wait.dismiss(); chooseImportSlot(a, r, objs, seconds); });
            } catch (Throwable e) {
                android.util.Log.e("OfflineTools", "recognition", e);
                a.runOnUiThread(() -> { wait.dismiss(); Toast.makeText(a, "Ошибка распознавания: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    private static void chooseImportSlot(Activity a, LayoutRecognizer r, List<LayoutRecognizer.Obj> objs, long seconds) {
        int walls = 0, heroes = 0;
        for (LayoutRecognizer.Obj o : objs) { if (o.name.equals("Wall")) walls++; else if (r.hero.containsKey(o.name)) heroes++; }
        String summary = "Найдено " + objs.size() + ": зданий " + (objs.size() - walls - heroes) + ", стен " + walls + ", героев " + heroes + " (" + seconds + " с).\nВ какую планировку поставить?";
        String[] names = new String[SLOTS.length - 1];
        for (int i = 1; i < SLOTS.length; i++) names[i - 1] = "Планировка " + (i + 1) + (i >= 3 && i < 6 ? " — военная" : "");
        cocDialog(a, "База распознана", summary, names, which -> {
            int slot = SLOTS[which + 1];
            confirm(a, "Заменить планировку " + (which + 2) + " распознанной базой?", () -> runEdit(a, () -> {
                File f = new File(a.getFilesDir(), "recognized-layout.json");
                write(f, r.importSpec(objs, slot).toString().getBytes(StandardCharsets.UTF_8));
                return importLayout(a, f);
            }));
        });
    }

    // Test hook (adb): files/recognize-test.json {"images": [paths]} -> files/recognize-test-out.json {objects, spec, ms}; no save is touched.
    private static int recognizeCheck;
    private static volatile boolean recognizeRunning;
    private static void checkRecognizeTest(Activity a) {
        if (recognizeRunning || ++recognizeCheck % 60 != 0) return;
        File f = new File(a.getFilesDir(), "recognize-test.json");
        if (!f.exists()) return;
        recognizeRunning = true;
        new Thread(() -> {
            JSONObject out = new JSONObject();
            try {
                JSONArray paths = new JSONObject(new String(read(f), StandardCharsets.UTF_8)).getJSONArray("images");
                long t0 = System.currentTimeMillis();
                LayoutRecognizer r = LayoutRecognizer.get(a);
                long t1 = System.currentTimeMillis();
                List<android.graphics.Bitmap> images = new ArrayList<>();
                for (int i = 0; i < paths.length(); i++) images.add(android.graphics.BitmapFactory.decodeFile(paths.getString(i)));
                List<LayoutRecognizer.Obj> objs = r.recognize(images, step -> {});
                JSONArray list = new JSONArray();
                for (LayoutRecognizer.Obj o : objs) list.put(new JSONObject().put("name", o.name).put("x", o.x).put("y", o.y).put("w", o.w).put("conf", o.conf));
                out.put("load_ms", t1 - t0).put("ms", System.currentTimeMillis() - t1).put("objects", list).put("spec", r.importSpec(objs, 10));
            } catch (Throwable e) { try { out.put("error", android.util.Log.getStackTraceString(e)); } catch (Exception ignored) {} }
            try { write(new File(a.getFilesDir(), "recognize-test-out.json"), out.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
            f.delete(); recognizeRunning = false;
        }).start();
    }

    // ---- Town Hall profiles: the own village plus villages maxed for TH 13..18, switched in place ----
    // files/profiles/<name>/ holds save-home.json + save-avatar.bin of every profile; files/profile.txt names the active one ("own" = the
    // player's village). A TH profile is built once from the own village (layouts and owned items kept): buildings and traps above the TH
    // count are removed, the rest go to the maximum level for that TH (assets/th-profiles.json, generated from the game tables), troops,
    // spells, heroes, pets and equipment too. Later visits load the profile as it was left.
    private static final int[] PROFILE_TH = {13, 14, 15, 16, 17, 18};

    private static String activeProfile(Activity a) {
        try { return new String(read(new File(a.getFilesDir(), "profile.txt")), StandardCharsets.UTF_8).trim(); } catch (Exception e) { return "own"; }
    }

    private static void chooseTownHallProfile(Activity a) {
        String active = activeProfile(a);
        String[] names = new String[PROFILE_TH.length + 1];
        names[0] = "Своя деревня" + ("own".equals(active) ? " ✓" : "");
        for (int i = 0; i < PROFILE_TH.length; i++) names[i + 1] = "ТХ" + PROFILE_TH[i] + " (максимум)" + (("th" + PROFILE_TH[i]).equals(active) ? " ✓" : "");
        gridDialog(a, "Ратуша", names, which -> {
            String target = which == 0 ? "own" : "th" + PROFILE_TH[which - 1];
            if (target.equals(active)) return;
            boolean exists = new File(a.getFilesDir(), "profiles/" + target + "/save-home.json").exists() || "own".equals(target);
            confirm(a, which == 0 ? "Вернуться в свою деревню?" : exists ? "Перейти в деревню ТХ" + PROFILE_TH[which - 1] + "?"
                : "Создать деревню ТХ" + PROFILE_TH[which - 1] + " из своей: здания, войска и герои на максимум для этой ратуши. Своя деревня сохранится.",
                () -> runEdit(a, () -> switchProfile(a, active, target)));
        });
    }

    private static void copyFile(File from, File to) throws Exception { to.getParentFile().mkdirs(); write(to, read(from)); }

    private static String switchProfile(Activity a, String active, String target) throws Exception {
        File files = a.getFilesDir();
        String[] saves = {"save-home.json", "save-avatar.bin"};
        for (String f : saves) copyFile(new File(files, f), new File(files, "profiles/" + active + "/" + f));
        File dir = new File(files, "profiles/" + target);
        if (new File(dir, "save-home.json").exists()) {
            for (String f : saves) copyFile(new File(dir, f), new File(files, f));
        } else {
            for (String f : saves) copyFile(new File(files, "profiles/own/" + f), new File(files, f));
            buildTownHallProfile(a, Integer.parseInt(target.substring(2)));
        }
        write(new File(files, "profile.txt"), target.getBytes(StandardCharsets.UTF_8));
        return "own".equals(target) ? "Своя деревня" : "Деревня ТХ" + target.substring(2);
    }

    private static void buildTownHallProfile(Activity a, int th) throws Exception {
        JSONObject all = new JSONObject(new String(asset(a, "th-profiles.json"), StandardCharsets.UTF_8));
        JSONObject prof = all.getJSONObject(String.valueOf(th)), merge = all.getJSONObject("merge"), sizes = all.getJSONObject("sizes");
        File homeFile = new File(a.getFilesDir(), "save-home.json");
        JSONObject home = new JSONObject(new String(read(homeFile), StandardCharsets.UTF_8));
        // Merged buildings not yet available at this TH become their two source buildings again: the first takes the merged building's
        // place (and layout positions), the second a free spot of the village (layouts keep it in storage). TH counts are applied after.
        JSONArray buildings = home.getJSONArray("buildings"), unmerged = new JSONArray();
        boolean[][] used = new boolean[50][50];
        long nextId = 0;
        for (int i = 0; i < buildings.length(); i++) {
            JSONObject b = buildings.getJSONObject(i); nextId = Math.max(nextId, b.getLong("id"));
            int w = sizes.optInt(b.getString("data"), 1), x = b.optInt("x", -1), y = b.optInt("y", -1);
            for (int dx = 0; x >= 0 && dx < w; dx++) for (int dy = 0; y >= 0 && dy < w; dy++) if (x + dx < 50 && y + dy < 50) used[x + dx][y + dy] = true;
        }
        java.util.Random random = new java.util.Random(th);
        for (int i = 0; i < buildings.length(); i++) {
            JSONObject b = buildings.getJSONObject(i);
            JSONArray sources = merge.optJSONArray(b.getString("data"));
            JSONArray limit = prof.getJSONObject("buildings").optJSONArray(b.getString("data"));
            if (sources == null || limit == null || limit.getInt(0) > 0) { unmerged.put(b); continue; }
            for (int k = 0; k < sources.length(); k++) {
                JSONObject src = new JSONObject(b.toString());
                src.put("data", sources.getLong(k)); src.put("id", ++nextId); src.remove("minilvl");
                if (k > 0) {
                    int w = sizes.optInt(String.valueOf(sources.getLong(k)), 3);
                    int[] spot = randomFreeSpot(used, random, w, 3, 3, 46, 46);
                    if (spot == null) continue;
                    java.util.Iterator<String> keys = new JSONObject(src.toString()).keys();
                    while (keys.hasNext()) { String key = keys.next(); if (key.matches("l\\d+x")) src.remove(key); }
                    src.put("x", spot[0]); src.put("y", spot[1]);
                }
                unmerged.put(src);
            }
        }
        home.put("buildings", unmerged);
        for (String[] kind : new String[][]{{"buildings", "buildings"}, {"traps", "traps"}}) {
            JSONArray items = home.optJSONArray(kind[0]), kept = new JSONArray();
            JSONObject limits = prof.getJSONObject(kind[1]);
            java.util.Map<Long, Integer> seen = new java.util.HashMap<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                JSONArray limit = limits.optJSONArray(String.valueOf(o.getLong("data")));
                if (limit == null) { kept.put(o); continue; }          // not a home-village item of the tables: unchanged
                int max = limit.getInt(0), count = limit.getInt(1), n = seen.merge(o.getLong("data"), 1, Integer::sum);
                if (max <= 0 || (count >= 0 && n > count)) continue;   // not unlocked at this TH, or above the TH count
                o.put("lvl", max - 1); o.remove("const_t"); o.remove("const_t_end");
                kept.put(o);
            }
            if (items != null) home.put(kind[0], kept);
        }
        write(homeFile, home.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject units = prof.getJSONObject("units");
        JSONArray catalog = unitCatalog(a);
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.getJSONObject(i);
            if (!units.has(String.valueOf(item.getLong("id")))) continue;
            int level = Math.max(1, Math.min(item.getInt("max"), units.getInt(String.valueOf(item.getLong("id")))));
            try { writeUnitLevel(a, item, level); } catch (IllegalArgumentException notOwned) { /* not in this save */ }
        }
        java.util.Map<Long, Long> owned = ownedEquipment(a);
        JSONArray eq = equipmentCatalog(a);
        for (int i = 0; i < eq.length(); i++) {
            JSONObject item = eq.getJSONObject(i); long id = item.getLong("id");
            if (owned.containsKey(id)) owned.put(id, (long) Math.max(0, Math.min(item.getInt("max"), Math.max(1, units.optInt(String.valueOf(id), item.getInt("max")))) - 1));
        }
        writeEquipment(a, owned);
    }

    // ---- battle replays: files/replays/<unix time>.json, written by the native side when a sandbox battle ends ----
    private static final int MAX_REPLAYS = 20;

    private static void showReplays(Activity a) {
        File dir = new File(a.getFilesDir(), "replays");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0) { Toast.makeText(a, "Повторов пока нет: они сохраняются после каждого боя", Toast.LENGTH_LONG).show(); return; }
        java.util.Arrays.sort(files, (x, y) -> y.getName().compareTo(x.getName()));
        for (int i = MAX_REPLAYS; i < files.length; i++) files[i].delete();
        int n = Math.min(files.length, MAX_REPLAYS);
        String[] names = new String[n];
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("d MMMM, HH:mm", new java.util.Locale("ru"));
        for (int i = 0; i < n; i++) {
            long seconds = Long.parseLong(files[i].getName().replace(".json", ""));
            names[i] = "Бой " + format.format(new java.util.Date(seconds * 1000));
        }
        File[] shown = files;
        // The game's own replay viewer (native side: replay receiver + recorded commands queued in the replay mode).
        cocDialog(a, "Повторы боёв", "Недавние бои песочницы", names, which -> { SandboxShop.closeAll(); requestReplay("replays/" + shown[which].getName()); });
    }

    // ---- army links (https://link.clashofclans.com/...?action=CopyArmy&army=...) ----
    // The original link handler (GameApp.handleDeeplinkURL) restarts the server login, which leaves the offline sandbox on the loading
    // screen, so the sandbox reads the army itself and saves it into a saved army slot. Army string: sections u (troops and siege
    // machines), s (spells), i / d (clan castle troops / spells) with "<count>x<id>" items, h (heroes) with "<id>[m<mode>][p<pet>][e<item>[_<item>]]"
    // items; items are separated by "-", ids are offsets from the GlobalID bases (4000000, 26000000, 28000000, 73000000, 90000000).
    private static final java.util.regex.Pattern GAME_LINK = java.util.regex.Pattern.compile("https?://link\\.clashofclans\\.com/\\S+");

    /** Keeps a link.clashofclans.com link for the sandbox; true when the intent carried one. */
    public static boolean savePendingLink(Activity a, Intent intent) {
        if (intent == null) return false;
        String link = null;
        android.net.Uri data = intent.getData();
        if (data != null && "link.clashofclans.com".equals(data.getHost())) link = data.toString();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) {  // "Share" from Telegram, a browser...: the link is in the shared text
            java.util.regex.Matcher m = GAME_LINK.matcher(String.valueOf(intent.getStringExtra(Intent.EXTRA_TEXT)));
            if (m.find()) link = m.group();
        }
        if (link == null) return false;
        try { write(new File(a.getFilesDir(), "pending-link.txt"), link.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { android.util.Log.e("OfflineTools", "link", e); }
        return true;
    }

    private static int linkCheck;
    private static void checkPendingLink(Activity a) {
        if (++linkCheck % 60 != 0 || !homeReady()) return;
        File f = new File(a.getFilesDir(), "pending-link.txt");
        if (!f.exists()) return;
        try {
            String url = new String(read(f), StandardCharsets.UTF_8).trim();
            f.delete();
            a.runOnUiThread(() -> importArmyLink(a, url));
        } catch (Exception e) { android.util.Log.e("OfflineTools", "link", e); }
    }

    // ---- progress import: the account export of the original game (JSON with "buildings", "traps", "units", "spells", "siege_machines",
    // "heroes", "pets", "equipment", "guardians": [{"data": id, "lvl": level from 1, "cnt": count}, ...]). Only levels are taken: units,
    // spells, siege machines, pets and heroes are set in the running game (also ones not yet in the save); buildings, traps, guardians
    // and equipment are written to the save, followed by one in-place reload. Builder base parts ("...2"), skins and sceneries are skipped.

    private static void importProgress(Activity a) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0)
            text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(a);
        JSONObject export = parseExport(text == null ? "" : text.toString());
        if (export != null) { confirmImport(a, export); return; }
        // No export in the clipboard: a field to paste it into.
        int H = SandboxShop.screenH(a);
        android.widget.LinearLayout body = new android.widget.LinearLayout(a); body.setOrientation(android.widget.LinearLayout.VERTICAL);
        body.addView(SandboxShop.text(a, "Скопируйте экспорт аккаунта из игры и вставьте его сюда", H * 0.03f, 0xFF3A3A33));
        android.widget.EditText field = new android.widget.EditText(a);
        field.setHint("{\"tag\": ..., \"buildings\": [...]}"); field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, H * 0.025f);
        field.setTextColor(0xFF3A3A33); field.setBackground(box(0xFFFFFFFF, 0xFFBDBDB3, 18)); field.setGravity(Gravity.TOP);
        field.setPadding(Math.round(H * 0.02f), Math.round(H * 0.015f), Math.round(H * 0.02f), Math.round(H * 0.015f));
        android.widget.LinearLayout.LayoutParams fl = new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.32f)); fl.topMargin = Math.round(H * 0.02f);
        body.addView(field, fl);
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        View go = SandboxShop.button(a, "Продолжить", SandboxShop.GREEN, H * 0.038f);
        go.setOnClickListener(v -> {
            JSONObject pasted = parseExport(field.getText().toString());
            if (pasted == null) { Toast.makeText(a, "Это не экспорт аккаунта: нет данных о зданиях и войсках", Toast.LENGTH_LONG).show(); return; }
            dialog[0].dismiss(); confirmImport(a, pasted);
        });
        android.widget.LinearLayout.LayoutParams gl = new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.11f)); gl.topMargin = Math.round(H * 0.02f);
        body.addView(go, gl);
        dialog[0] = SandboxShop.popup(a, "Импорт прокачки", body, 0.5f);
        dialog[0].getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog[0].show();
    }

    private static JSONObject parseExport(String text) {
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JSONObject o = new JSONObject(text.substring(start, end + 1));
            for (String key : new String[]{"buildings", "units", "spells", "heroes"}) if (o.optJSONArray(key) != null) return o;
        } catch (Exception ignored) {}
        return null;
    }

    /** export key -> list of {data, lvl (from 1), cnt}; entries without a numeric data id are skipped. */
    private static List<long[]> exportEntries(JSONObject export, String key) {
        List<long[]> out = new ArrayList<>();
        JSONArray list = export.optJSONArray(key);
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e == null || !e.has("data") || !e.has("lvl")) continue;
            out.add(new long[]{e.optLong("data"), e.optLong("lvl"), Math.max(1, e.optLong("cnt", 1)), e.optLong("supercharge", -1)});
        }
        return out;
    }

    private static void confirmImport(Activity a, JSONObject export) {
        int units = exportEntries(export, "units").size() + exportEntries(export, "spells").size() + exportEntries(export, "siege_machines").size() + exportEntries(export, "pets").size();
        String summary = "Будут перенесены уровни:\n"
            + "войска, заклинания, осадные машины и питомцы: " + units + "\n"
            + "герои: " + exportEntries(export, "heroes").size() + ", снаряжение: " + exportEntries(export, "equipment").size() + "\n"
            + "здания: " + exportEntries(export, "buildings").size() + " групп, ловушки: " + exportEntries(export, "traps").size() + " групп\n"
            + "Расстановка не меняется. Деревня перезагрузится.";
        cocDialog(a, "Импорт прокачки", summary, new String[]{"Перенести"}, w -> applyImport(a, export));
    }

    private static void applyImport(Activity a, JSONObject export) {
        try {
            // Units, spells, siege machines, pets, heroes: in the running game (the game adds levels missing from its lists); the save flush
            // of the edit below then stores them.
            JSONArray catalog = unitCatalog(a);
            java.util.Map<Long, JSONObject> byId = new java.util.HashMap<>();
            for (int i = 0; i < catalog.length(); i++) byId.put(catalog.getJSONObject(i).getLong("id"), catalog.getJSONObject(i));
            int live = 0;
            for (String key : new String[]{"units", "spells", "siege_machines", "pets", "heroes"})
                for (long[] e : exportEntries(export, key)) {
                    JSONObject item = byId.get(e[0]);
                    if (item == null || "1".equals(item.optString("village"))) continue;
                    int level = (int) Math.max(1, Math.min(item.getInt("max"), e[1]));
                    if ("heroes".equals(item.getString("group"))) liveUnitLevel(a, item, level);
                    else setLiveUnitLevel((int) e[0], level - 1);
                    live++;
                }
            final int liveCount = live;
            // Give the logic thread a moment to apply them before the save is flushed for the file edit.
            new Handler(Looper.getMainLooper()).postDelayed(() -> runEdit(a, () -> liveCount + " уровней армии. " + importSaveLevels(a, export)), 1500);
        } catch (Exception e) { Toast.makeText(a, "Ошибка импорта: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** Buildings, traps, guardians (save-home.json) and equipment (save-avatar.bin) from the export. */
    private static String importSaveLevels(Activity a, JSONObject export) throws Exception {
        File file = new File(a.getFilesDir(), "save-home.json");
        JSONObject home = new JSONObject(new String(read(file), StandardCharsets.UTF_8));
        java.util.Map<Long, Integer> max = new java.util.HashMap<>();
        JSONArray catalog = buildingGuardianCatalog(a);
        for (int i = 0; i < catalog.length(); i++) { JSONObject it = catalog.getJSONObject(i); max.put(it.getLong("id"), it.getInt("max")); }
        int buildings = 0, traps = 0, guardians = 0, equipment = 0;
        for (String key : new String[]{"buildings", "traps"}) {
            // Levels of one type, highest first, go to that type's objects in the save, highest first; extra objects get the lowest level.
            java.util.Map<Long, List<long[]>> levels = new java.util.HashMap<>();
            for (long[] e : exportEntries(export, key))
                for (int c = 0; c < e[2]; c++) levels.computeIfAbsent(e[0], k -> new ArrayList<>()).add(new long[]{e[1], e[3]});
            JSONArray items = home.optJSONArray(key);
            java.util.Map<Long, List<JSONObject>> objects = new java.util.HashMap<>();
            for (int i = 0; items != null && i < items.length(); i++) objects.computeIfAbsent(items.getJSONObject(i).optLong("data"), k -> new ArrayList<>()).add(items.getJSONObject(i));
            for (java.util.Map.Entry<Long, List<long[]>> type : levels.entrySet()) {
                List<JSONObject> list = objects.get(type.getKey());
                if (list == null) continue;
                List<long[]> wanted = type.getValue();
                wanted.sort((x, y) -> Long.compare(y[0], x[0]));
                list.sort((x, y) -> Integer.compare(y.optInt("lvl", 0), x.optInt("lvl", 0)));
                Integer cap = max.get(type.getKey());
                for (int i = 0; i < list.size(); i++) {
                    long[] w = wanted.get(Math.min(i, wanted.size() - 1));
                    int level = (int) Math.max(1, cap == null ? w[0] : Math.min(cap, w[0]));
                    JSONObject o = list.get(i);
                    if (o.optInt("const_t", 0) > 0) continue;  // under construction: left as it is
                    o.put("lvl", level - 1);
                    if (w[1] >= 0) o.put("minilvl", w[1]);
                    if ("buildings".equals(key)) buildings++; else traps++;
                }
            }
        }
        JSONObject manager = home.optJSONObject("guardian_manager");
        JSONArray owned = manager == null ? null : manager.optJSONArray("m_pGuardians");
        for (long[] e : exportEntries(export, "guardians"))
            for (int i = 0; owned != null && i < owned.length(); i++) {
                JSONObject g = owned.getJSONObject(i);
                if (g.optLong("m_pGuardian") != e[0]) continue;
                Integer cap = max.get(e[0]);
                g.put("m_pGuardianUpgradeLevel", (int) Math.max(0, (cap == null ? e[1] : Math.min(cap, e[1])) - 1)); guardians++;
            }
        write(file, home.toString().getBytes(StandardCharsets.UTF_8));
        List<long[]> gear = exportEntries(export, "equipment");
        if (!gear.isEmpty()) {
            java.util.Map<Long, Long> levels = ownedEquipment(a);
            JSONArray eqCatalog = equipmentCatalog(a);
            java.util.Map<Long, Integer> eqMax = new java.util.HashMap<>();
            for (int i = 0; i < eqCatalog.length(); i++) eqMax.put(eqCatalog.getJSONObject(i).getLong("id"), eqCatalog.getJSONObject(i).getInt("max"));
            for (long[] e : gear) { Integer cap = eqMax.get(e[0]); if (cap == null) continue; levels.put(e[0], Math.max(0, Math.min(cap, e[1]) - 1)); equipment++; }
            writeEquipment(a, levels);
        }
        return "Здания: " + buildings + ", ловушки: " + traps + ", стражи: " + guardians + ", снаряжение: " + equipment;
    }

    // ---- player profile (social popup, profile tab): name and clan name are the two LogicString fields (op 0x30) of the avatar save,
    // clan first, then the player name.

    private static String[] avatarNames(Activity a) throws Exception {
        byte[] data = read(new File(a.getFilesDir(), "save-avatar.bin"));
        String[] out = {"", ""}; int found = 0;
        for (Op op : parseOps(data)) {
            if (op.method != 0x30 || found >= 2) continue;
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); buf.position(op.offset);
            boolean present = buf.get() != 0; int len = buf.getInt();
            out[found++] = present ? new String(data, buf.position(), len, StandardCharsets.UTF_8) : "";
        }
        return out;
    }

    /** Rewrites the n-th LogicString field (0 = clan name, 1 = player name) of the avatar save. */
    private static String setAvatarName(Activity a, int which, String value) throws Exception {
        File file = new File(a.getFilesDir(), "save-avatar.bin"); byte[] data = read(file);
        int seen = 0;
        for (Op op : parseOps(data)) {
            if (op.method != 0x30 || seen++ != which) continue;
            int len = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(op.offset + 1);
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(data, 0, op.offset);
            ByteBuffer field = ByteBuffer.allocate(5 + text.length).order(ByteOrder.LITTLE_ENDIAN); field.put((byte) 1).putInt(text.length).put(text);
            o.write(field.array());
            int end = op.offset + 5 + len; o.write(data, end, data.length - end);
            byte[] result = o.toByteArray();
            parseOps(result);  // refuse a broken rewrite
            write(file, result);
            return which == 0 ? "Клан: " + value : "Ник: " + value;
        }
        throw new IllegalArgumentException("Поле имени не найдено в сохранении");
    }

    private static void editName(Activity a, int which, String current) {
        int H = SandboxShop.screenH(a);
        android.widget.LinearLayout body = new android.widget.LinearLayout(a); body.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.EditText field = new android.widget.EditText(a);
        field.setText(current); field.setSingleLine(true); field.setSelectAllOnFocus(true); field.setTypeface(font(a));
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, H * 0.04f); field.setTextColor(0xFF3A3A33); field.setGravity(Gravity.CENTER);
        field.setBackground(box(0xFFFFFFFF, 0xFFBDBDB3, 18)); field.setPadding(Math.round(H * 0.02f), Math.round(H * 0.015f), Math.round(H * 0.02f), Math.round(H * 0.015f));
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(which == 1 ? 15 : 30)});
        body.addView(field, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.app.Dialog[] dialog = new android.app.Dialog[1];
        View save = SandboxShop.button(a, "Сохранить", SandboxShop.GREEN, H * 0.038f);
        save.setOnClickListener(v -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty() && which == 1) { Toast.makeText(a, "Ник не может быть пустым", Toast.LENGTH_SHORT).show(); return; }
            dialog[0].dismiss();
            runEdit(a, () -> setAvatarName(a, which, value));
        });
        android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.11f)); sl.topMargin = Math.round(H * 0.025f);
        body.addView(save, sl);
        dialog[0] = SandboxShop.popup(a, which == 1 ? "Изменить ник" : "Изменить клан", body, 0.42f);
        dialog[0].getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog[0].show();
    }

    /** Profile tab content: town hall, name and clan with edit buttons, the heroes with their levels. */
    private static void fillProfile(Activity a, FrameLayout panel) {
        panel.removeAllViews();
        panel.setBackgroundColor(0xFFEAEAE2);
        panel.setOnClickListener(v -> {});  // keeps taps off the game's own page below
        int H = SandboxShop.screenH(a); float u = H / 1272f;
        try {
            String[] names = avatarNames(a);
            JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
            int th = 1; JSONArray buildings = home.optJSONArray("buildings");
            for (int i = 0; buildings != null && i < buildings.length(); i++) if (buildings.getJSONObject(i).optLong("data") == 1000001) th = buildings.getJSONObject(i).optInt("lvl", 0) + 1;
            android.widget.LinearLayout column = new android.widget.LinearLayout(a); column.setOrientation(android.widget.LinearLayout.VERTICAL);
            column.setPadding(Math.round(40 * u), Math.round(30 * u), Math.round(40 * u), Math.round(30 * u));
            panel.addView(column, new FrameLayout.LayoutParams(-1, -1));
            // Header card: town hall, name, clan, edit buttons.
            android.widget.LinearLayout head = new android.widget.LinearLayout(a); head.setGravity(Gravity.CENTER_VERTICAL);
            head.setBackground(new SandboxShop.CardDrawable(24 * u, false)); head.setPadding(Math.round(30 * u), Math.round(20 * u), Math.round(30 * u), Math.round(20 * u));
            android.widget.ImageView hall = new android.widget.ImageView(a); hall.setImageBitmap(SandboxShop.icon(a, 1000001)); hall.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            head.addView(hall, new android.widget.LinearLayout.LayoutParams(Math.round(260 * u), Math.round(260 * u)));
            android.widget.LinearLayout who = new android.widget.LinearLayout(a); who.setOrientation(android.widget.LinearLayout.VERTICAL);
            who.addView(new SandboxShop.OutlineText(a, names[1], 70 * u, 0xFFFFFFFF, android.text.Layout.Alignment.ALIGN_NORMAL), new android.widget.LinearLayout.LayoutParams(-1, -2));
            who.addView(new SandboxShop.OutlineText(a, names[0].isEmpty() ? "Без клана" : names[0], 42 * u, 0xFFFFFDE6, android.text.Layout.Alignment.ALIGN_NORMAL), new android.widget.LinearLayout.LayoutParams(-1, -2));
            who.addView(new SandboxShop.OutlineText(a, "Ратуша " + th, 36 * u, 0xFFFFE27A, android.text.Layout.Alignment.ALIGN_NORMAL), new android.widget.LinearLayout.LayoutParams(-1, -2));
            android.widget.LinearLayout.LayoutParams wl = new android.widget.LinearLayout.LayoutParams(0, -2, 1); wl.leftMargin = Math.round(30 * u);
            head.addView(who, wl);
            android.widget.LinearLayout edits = new android.widget.LinearLayout(a); edits.setOrientation(android.widget.LinearLayout.VERTICAL);
            View nick = SandboxShop.button(a, "Изменить ник", SandboxShop.BLUE, 34 * u), clan = SandboxShop.button(a, "Изменить клан", SandboxShop.BLUE, 34 * u);
            nick.setOnClickListener(v -> editName(a, 1, names[1])); clan.setOnClickListener(v -> editName(a, 0, names[0]));
            edits.addView(nick, new android.widget.LinearLayout.LayoutParams(Math.round(420 * u), Math.round(110 * u)));
            android.widget.LinearLayout.LayoutParams cl = new android.widget.LinearLayout.LayoutParams(Math.round(420 * u), Math.round(110 * u)); cl.topMargin = Math.round(20 * u);
            edits.addView(clan, cl);
            head.addView(edits);
            column.addView(head, new android.widget.LinearLayout.LayoutParams(-1, -2));
            // Heroes.
            android.widget.LinearLayout.LayoutParams hl = new android.widget.LinearLayout.LayoutParams(-1, -2); hl.topMargin = Math.round(30 * u);
            column.addView(SandboxShop.text(a, "Герои", 44 * u, 0xFF3A3A33), hl);
            JSONArray catalog = unitCatalog(a);
            List<Op> ops = parseOps(read(new File(a.getFilesDir(), "save-avatar.bin")));
            int at = findLevelList(ops, unitLimits(catalog, "heroes"));
            java.util.Map<Long, Long> levels = new java.util.HashMap<>();
            for (int k = 0; k < ops.get(at).value; k++) levels.put(ops.get(at + 1 + 2 * k).value, ops.get(at + 2 + 2 * k).value);
            android.widget.LinearLayout row = new android.widget.LinearLayout(a); row.setGravity(Gravity.CENTER);
            List<JSONObject> heroes = new ArrayList<>();
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject item = catalog.getJSONObject(i);
                if ("heroes".equals(item.getString("group")) && levels.containsKey(item.getLong("id")) && !"1".equals(item.optString("village"))) heroes.add(item);
            }
            // All home-village heroes in one row: tiles shrink to the panel width.
            int tile = Math.min(Math.round(300 * u), Math.round((SOCIAL_CONTENT.width() * u - 80 * u - 20 * u * (heroes.size() - 1)) / Math.max(1, heroes.size()) / 0.85f));
            for (JSONObject item : heroes) {
                int level = (int) (long) levels.get(item.getLong("id")) + 1, max = item.getInt("max");
                View t = SandboxShop.tile(a, item.getString("name"), level == max ? "МАКС" : level + "/" + max, level == max, SandboxShop.icon(a, item.getLong("id")), Math.round(tile * 0.85f), tile);
                android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(Math.round(tile * 0.85f), tile); if (row.getChildCount() > 0) tl.leftMargin = Math.round(20 * u);
                row.addView(t, tl);
            }
            android.widget.LinearLayout.LayoutParams rl = new android.widget.LinearLayout.LayoutParams(-1, -2); rl.topMargin = Math.round(14 * u);
            column.addView(row, rl);
        } catch (Exception e) { panel.addView(SandboxShop.text(a, "Профиль недоступен: " + e.getMessage(), 36 * u, 0xFF3A3A33)); }
    }

    private static void armyFromClipboard(Activity a) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0)
            text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(a);
        java.util.regex.Matcher m = GAME_LINK.matcher(text == null ? "" : text);
        if (!m.find()) { Toast.makeText(a, "Скопируйте ссылку на армию (link.clashofclans.com…), затем нажмите снова", Toast.LENGTH_LONG).show(); return; }
        importArmyLink(a, m.group());
    }

    private static void importArmyLink(Activity a, String url) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String army = uri.getQueryParameter("army");
            if (!"CopyArmy".equalsIgnoreCase(uri.getQueryParameter("action")) || army == null) throw new IllegalArgumentException("это не ссылка на армию");
            JSONObject loadout = parseArmy(a, army);
            JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
            JSONArray saved = home.optJSONArray("saved_army_loadouts");
            if (saved == null || saved.length() == 0) throw new IllegalArgumentException("в сохранении нет слотов армий");
            String[] names = new String[saved.length()];
            for (int i = 0; i < names.length; i++) {
                String n = saved.getJSONObject(i).optString("m_customName", "");
                names[i] = (i + 1) + ". " + (n.isEmpty() ? "Армия" : n);
            }
            cocDialog(a, "Армия по ссылке", armySummary(loadout) + "\nВ какой слот сохранить?", names, slot -> runEdit(a, () -> {
                File f = new File(a.getFilesDir(), "save-home.json");
                JSONObject latest = new JSONObject(new String(read(f), StandardCharsets.UTF_8));
                JSONObject target = latest.getJSONArray("saved_army_loadouts").getJSONObject(slot);
                target.put("slots_by_type", loadout.getJSONArray("slots_by_type"));  // slot type and name stay
                write(f, latest.toString().getBytes(StandardCharsets.UTF_8));
                return "Армия сохранена в слот " + (slot + 1);
            }));
        } catch (Exception e) { Toast.makeText(a, "Ссылка не прочитана: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private static JSONObject parseArmy(Activity a, String army) throws Exception {
        java.util.Set<Long> siege = new java.util.HashSet<>();
        JSONArray siegeIds = new JSONArray(new String(asset(a, "siege-ids.json"), StandardCharsets.UTF_8));
        for (int i = 0; i < siegeIds.length(); i++) siege.add(siegeIds.getLong(i));
        JSONArray troops = new JSONArray(), spells = new JSONArray(), sieges = new JSONArray(), castle = new JSONArray(), heroes = new JSONArray();
        java.util.regex.Matcher section = java.util.regex.Pattern.compile("([ushid])([^ushid]*)").matcher(army);
        while (section.find()) {
            char kind = section.group(1).charAt(0);
            for (String item : section.group(2).split("-")) {
                if (item.isEmpty()) continue;
                if (kind == 'h') {
                    java.util.regex.Matcher h = java.util.regex.Pattern.compile("(\\d+)(?:m(\\d+))?(?:p(\\d+))?(?:e(\\d+)(?:_(\\d+))?)?").matcher(item);
                    if (!h.matches()) continue;
                    JSONObject hero = new JSONObject().put("id", 28000000L + Long.parseLong(h.group(1)));
                    if (h.group(3) != null) hero.put("m_pPetData", 73000000L + Long.parseLong(h.group(3)));
                    JSONArray items = new JSONArray();
                    if (h.group(4) != null) items.put(90000000L + Long.parseLong(h.group(4)));
                    if (h.group(5) != null) items.put(90000000L + Long.parseLong(h.group(5)));
                    heroes.put(hero.put("m_itemDatas", items).put("m_heroMode", h.group(2) == null ? 0 : Integer.parseInt(h.group(2))));
                    continue;
                }
                String[] cx = item.split("x");
                if (cx.length != 2) continue;
                int count = Integer.parseInt(cx[0]); long id = Long.parseLong(cx[1]);
                if (count <= 0) continue;
                switch (kind) {
                    case 'u': { long gid = 4000000L + id; (siege.contains(gid) ? sieges : troops).put(new JSONObject().put("id", gid).put("m_count", count)); break; }
                    case 's': spells.put(new JSONObject().put("id", 26000000L + id).put("m_count", count)); break;
                    case 'i': castle.put(new JSONObject().put("id", 4000000L + id).put("m_count", count).put("m_level", 0)); break;
                    case 'd': castle.put(new JSONObject().put("id", 26000000L + id).put("m_count", count).put("m_level", 0)); break;
                }
            }
        }
        if (troops.length() + spells.length() + sieges.length() + heroes.length() == 0) throw new IllegalArgumentException("армия пустая");
        return new JSONObject().put("slots_by_type", new JSONArray().put(troops).put(spells).put(sieges).put(castle).put(heroes));
    }

    private static String armySummary(JSONObject loadout) throws Exception {
        JSONArray s = loadout.getJSONArray("slots_by_type"); int troops = 0, spells = 0;
        for (int i = 0; i < s.getJSONArray(0).length(); i++) troops += s.getJSONArray(0).getJSONObject(i).getInt("m_count");
        for (int i = 0; i < s.getJSONArray(1).length(); i++) spells += s.getJSONArray(1).getJSONObject(i).getInt("m_count");
        return "Войск: " + troops + ", заклинаний: " + spells + ", осадных машин: " + s.getJSONArray(2).length() + ", героев: " + s.getJSONArray(4).length()
            + (s.getJSONArray(3).length() > 0 ? ", замок клана: " + s.getJSONArray(3).length() : "");
    }

    // ---- attack target chooser (answers the original "Подбор" matchmaking) ----

    private static native boolean isMatchmakingWaiting();
    private static volatile boolean chooserOpen;
    /** Layout slots as listed by the patched layout editor: home 0,2,3, war 1,4,5, extra 8,9,10. Slot 0 = x/y, slot N = "lNx". */
    private static final int[] SLOTS = {0, 2, 3, 1, 4, 5, 8, 9, 10};

    /** Called every logic frame (GameApp.beforeLogicCallback). */
    public static void tick(Activity a) {
        genPinch(a);
        genNext(a);
        checkLayoutImport(a);
        checkRecognizeTest(a);
        checkSceneryChoice(a);
        checkPendingLink(a);
        if (!isMatchmakingWaiting()) { chooserOpen = false; return; }
        if (chooserOpen) return;
        chooserOpen = true;
        a.runOnUiThread(() -> showTargetChooser(a));
    }

    // Dataset generator fast cycle: files/gen-next.txt during a battle -> the pushed gen-home.json becomes the target and a new attack starts
    // directly from the running one (native restartLocalAttack), skipping surrender, the end screen and the village load.
    private static int nextCheck;
    private static void genNext(Activity a) {
        if (++nextCheck % 30 != 0) return;
        File flag = new File(a.getFilesDir(), "gen-next.txt"), gen = new File(a.getFilesDir(), "gen-home.json");
        if (!flag.exists() || !gen.exists() || !isBattleActive()) return;
        flag.delete();
        try {
            JSONObject home = new JSONObject(new String(read(gen), StandardCharsets.UTF_8));
            write(new File(a.getFilesDir(), "attack-home.json"), home.toString().getBytes(StandardCharsets.UTF_8));
            prepareAttackAvatar(a, new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8)));
            if (!gen.delete()) android.util.Log.w("OfflineTools", "gen-home.json not removed");
            requestRestartAttack();
        } catch (Exception e) { android.util.Log.e("OfflineTools", "gen-next", e); }
    }

    // Dataset generator camera control: files/gen-pinch.txt "<from> <to>" (half finger distance in px) is played as a two-finger pinch around the screen centre.
    private static int pinchCheck;
    private static void genPinch(Activity a) {
        if (++pinchCheck % 30 != 0) return;
        File f = new File(a.getFilesDir(), "gen-pinch.txt");
        if (!f.exists()) return;
        float from, to;
        try { String[] v = new String(read(f), StandardCharsets.UTF_8).trim().split("\\s+"); from = Float.parseFloat(v[0]); to = Float.parseFloat(v[1]); }
        catch (Exception e) { return; } finally { f.delete(); }
        a.runOnUiThread(() -> {
            android.util.DisplayMetrics d = a.getResources().getDisplayMetrics();
            float cx = d.widthPixels / 2f, cy = d.heightPixels / 2f; long t0 = android.os.SystemClock.uptimeMillis();
            Handler h = new Handler(Looper.getMainLooper()); int steps = 20;
            for (int i = 0; i <= steps + 1; i++) {
                final int k = i;
                h.postDelayed(() -> {
                    float dist = from + (to - from) * Math.min(k, steps) / steps;
                    android.view.MotionEvent.PointerProperties[] pp = {new android.view.MotionEvent.PointerProperties(), new android.view.MotionEvent.PointerProperties()};
                    pp[0].id = 0; pp[1].id = 1; pp[0].toolType = pp[1].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
                    android.view.MotionEvent.PointerCoords[] pc = {new android.view.MotionEvent.PointerCoords(), new android.view.MotionEvent.PointerCoords()};
                    pc[0].x = cx - dist; pc[1].x = cx + dist; pc[0].y = pc[1].y = cy; pc[0].pressure = pc[1].pressure = 1;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (k == 0) {
                        RenderActivity.forwardTouch(android.view.MotionEvent.obtain(t0, now, android.view.MotionEvent.ACTION_DOWN, 1, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0));
                        RenderActivity.forwardTouch(android.view.MotionEvent.obtain(t0, now, android.view.MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 2, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0));
                    } else if (k <= steps) {
                        RenderActivity.forwardTouch(android.view.MotionEvent.obtain(t0, now, android.view.MotionEvent.ACTION_MOVE, 2, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0));
                    } else {
                        RenderActivity.forwardTouch(android.view.MotionEvent.obtain(t0, now, android.view.MotionEvent.ACTION_POINTER_UP | (1 << 8), 2, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0));
                        RenderActivity.forwardTouch(android.view.MotionEvent.obtain(t0, now, android.view.MotionEvent.ACTION_UP, 1, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0));
                    }
                }, i * 16L);
            }
        });
    }

    private static void showTargetChooser(Activity a) {
        // Layout-recognition dataset generator: a pushed gen-home.json (complete home, layout in slot 0) is attacked directly, without the chooser.
        File gen = new File(a.getFilesDir(), "gen-home.json");
        if (gen.exists()) {
            try {
                JSONObject home = new JSONObject(new String(read(gen), StandardCharsets.UTF_8));
                write(new File(a.getFilesDir(), "attack-home.json"), home.toString().getBytes(StandardCharsets.UTF_8));
                prepareAttackAvatar(a, new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8)));
                if (!gen.delete()) android.util.Log.w("OfflineTools", "gen-home.json not removed");
                RenderActivity.startLocalAttack();
            } catch (Exception e) { Toast.makeText(a, "Ошибка генератора: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            return;
        }
        List<Integer> slots = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
            int active = home.optInt("active_layout", 0);
            for (int i = 0; i < SLOTS.length; i++) {
                int placed = countPlaced(home, SLOTS[i]);
                if (placed == 0) continue;
                slots.add(SLOTS[i]);
                names.add("Планировка " + (i + 1) + (SLOTS[i] == active ? " (выбранная)" : "") + (i >= 3 && i < 6 ? " — военная" : ""));
            }
        } catch (Exception e) { android.util.Log.e("OfflineTools", "chooser", e); }
        if (slots.isEmpty()) { slots.add(0); names.add("Текущая деревня"); }
        cocDialog(a, "Цель атаки", "Выберите планировку, на которую напасть", names.toArray(new String[0]), which -> {
            try {
                prepareAttackHome(a, slots.get(which));
                RenderActivity.startLocalAttack();
            } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
    }

    private static int countPlaced(JSONObject home, int slot) throws Exception {
        int n = 0;
        JSONArray items = home.optJSONArray("buildings");
        if (items == null) return 0;
        for (int i = 0; i < items.length(); i++) {
            int[] p = position(items.getJSONObject(i), slot);
            if (p != null && p[0] >= 0) n++;
        }
        return n;
    }


    /** Units of an army loadout (troops, spells, siege machines = slots_by_type[0..2]) as (id, count) pairs. */
    private static List<long[]> loadoutUnits(JSONObject loadout) throws Exception {
        List<long[]> out = new ArrayList<>();
        JSONArray types = loadout == null ? null : loadout.optJSONArray("slots_by_type");
        for (int t = 0; types != null && t < 3 && t < types.length(); t++) {
            JSONArray slots = types.getJSONArray(t);
            for (int i = 0; i < slots.length(); i++) out.add(new long[]{slots.getJSONObject(i).getLong("id"), slots.getJSONObject(i).getLong("m_count")});
        }
        return out;
    }

    /**
     * attack-avatar.bin: the saved avatar with its friendly-challenge army list (count N, then N x (0x50 id, 0x88 count)) replaced by the main army,
     * because the friendly-challenge battle deploys that list.
     */
    // ---- defending clan castle (files/defense-cc.json [{id, cnt, lvl}]): the troops, spells and siege machines that come out of the
    // castle when the sandbox base is attacked. Written into the defender's alliance units (save list: 0x50 N, N x (0x50 id, 0x88 count,
    // 0x88 level index)) of defense-avatar.bin, which probe.cpp decodes as the defender.

    private static JSONArray defenseCastle(Activity a) {
        try { return new JSONArray(new String(read(new File(a.getFilesDir(), "defense-cc.json")), StandardCharsets.UTF_8)); } catch (Exception e) { return new JSONArray(); }
    }

    /** Clan castle capacity {troops, spells, siege machines} of the castle level in the save (buildings.csv HousingSpace / Alt / Siege). */
    private static int[] castleCapacity(Activity a) {
        int[][] byLevel = {{10,0,0},{15,0,0},{20,0,0},{25,1,0},{30,1,0},{35,1,1},{35,2,1},{40,2,1},{40,2,1},{45,2,1},{50,3,1},{50,3,2},{55,3,2},{55,4,2},{60,4,2},{60,4,2}};
        try {
            JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
            JSONArray b = home.optJSONArray("buildings");
            for (int i = 0; b != null && i < b.length(); i++) if (b.getJSONObject(i).optLong("data") == 1000014) return byLevel[Math.min(byLevel.length - 1, b.getJSONObject(i).optInt("lvl", 0))];
        } catch (Exception ignored) {}
        return byLevel[13];
    }

    private static void defenseCastleWindow(Activity a) {
        try {
            int H = SandboxShop.screenH(a), W = SandboxShop.screenW(a);
            JSONArray catalog = unitCatalog(a);
            java.util.Map<Long, JSONObject> byId = new java.util.HashMap<>();
            for (int i = 0; i < catalog.length(); i++) byId.put(catalog.getJSONObject(i).getLong("id"), catalog.getJSONObject(i));
            // Player's own level of each unit (upgrade lists of the save), used for the castle troops.
            List<Op> ops = parseOps(read(new File(a.getFilesDir(), "save-avatar.bin")));
            java.util.Map<Long, Long> levels = new java.util.HashMap<>();
            for (String group : new String[]{"troops", "spells", "sieges"}) {
                try { int at = findLevelList(ops, unitLimits(catalog, group)); for (int k = 0; k < ops.get(at).value; k++) levels.put(ops.get(at + 1 + 2 * k).value, ops.get(at + 2 + 2 * k).value); } catch (Exception ignored) {}
            }
            JSONArray picked = defenseCastle(a);
            int[] cap = castleCapacity(a);
            android.widget.LinearLayout body = new android.widget.LinearLayout(a); body.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.TextView summary = (android.widget.TextView) SandboxShop.text(a, "", H * 0.032f, 0xFF3A3A33);
            body.addView(summary, new android.widget.LinearLayout.LayoutParams(-1, -2));
            android.widget.LinearLayout grid = new android.widget.LinearLayout(a); grid.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.ScrollView scroll = new android.widget.ScrollView(a); scroll.addView(grid);
            android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.36f)); sl.topMargin = Math.round(H * 0.015f);
            body.addView(scroll, sl);
            android.widget.LinearLayout buttons = new android.widget.LinearLayout(a);
            String[] groups = {"troops", "spells", "sieges"}, labels = {"Войска", "Заклинания", "Осадные машины"};
            float width = 0.62f; int columns = 6, gap = Math.round(H * 0.012f);
            int tw = (Math.round(W * width) - Math.round(H * 0.084f) - (columns - 1) * gap) / columns, th = Math.round(tw * 1.05f);
            java.util.function.ToIntFunction<String> used = g -> {
                int sum = 0;
                for (int i = 0; i < picked.length(); i++) {
                    JSONObject u = picked.optJSONObject(i); JSONObject item = byId.get(u.optLong("id"));
                    if (item != null && g.equals(item.optString("group"))) sum += u.optInt("cnt") * ("sieges".equals(g) ? 1 : Math.max(1, item.optInt("housing", 1)));
                }
                return sum;
            };
            Runnable save = () -> { try { write(new File(a.getFilesDir(), "defense-cc.json"), picked.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {} };
            Runnable[] render = new Runnable[1];
            render[0] = () -> {
                summary.setText("Войска " + used.applyAsInt("troops") + "/" + cap[0] + " · Заклинания " + used.applyAsInt("spells") + "/" + cap[1] + " · Машины " + used.applyAsInt("sieges") + "/" + cap[2]);
                grid.removeAllViews();
                android.widget.LinearLayout row = null; int inRow = 0;
                for (int i = 0; i < picked.length(); i++) {
                    JSONObject u = picked.optJSONObject(i); JSONObject item = byId.get(u.optLong("id")); if (item == null) continue;
                    final int index = i;
                    View t = SandboxShop.tile(a, item.optString("name"), "×" + u.optInt("cnt") + " · ур. " + (u.optInt("lvl") + 1), false, SandboxShop.icon(a, u.optLong("id")), tw, th);
                    t.setOnClickListener(v -> {  // a tap takes one away
                        int c = u.optInt("cnt") - 1;
                        try { if (c <= 0) picked.remove(index); else u.put("cnt", c); } catch (Exception ignored) {}
                        save.run(); render[0].run();
                    });
                    if (row == null || inRow == columns) { row = new android.widget.LinearLayout(a); inRow = 0; android.widget.LinearLayout.LayoutParams rl = new android.widget.LinearLayout.LayoutParams(-1, th); if (grid.getChildCount() > 0) rl.topMargin = gap; grid.addView(row, rl); }
                    android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(tw, th); if (inRow > 0) tl.leftMargin = gap;
                    row.addView(t, tl); inRow++;
                }
                if (picked.length() == 0) grid.addView(SandboxShop.text(a, "Крепость пуста. Добавьте войска кнопками ниже.", H * 0.028f, 0xFF6A6A60));
            };
            for (int g = 0; g < groups.length; g++) {
                final int gi = g;
                View b = SandboxShop.button(a, labels[g], SandboxShop.BLUE, H * 0.03f);
                b.setOnClickListener(v -> {
                    try {
                        List<JSONObject> items = new ArrayList<>(); List<String> names = new ArrayList<>();
                        for (int i = 0; i < catalog.length(); i++) {
                            JSONObject item = catalog.getJSONObject(i);
                            if (!groups[gi].equals(item.getString("group")) || "1".equals(item.optString("village")) || !levels.containsKey(item.getLong("id"))) continue;
                            items.add(item); names.add(item.getString("name") + " · место " + Math.max(1, item.optInt("housing", 1)));
                        }
                        long[] ids = new long[items.size()]; for (int i = 0; i < ids.length; i++) ids[i] = items.get(i).getLong("id");
                        searchDialog(a, labels[gi], names.toArray(new String[0]), ids, which -> {
                            try {
                                JSONObject item = items.get(which); long id = item.getLong("id");
                                int size = "sieges".equals(groups[gi]) ? 1 : Math.max(1, item.optInt("housing", 1));
                                if (used.applyAsInt(groups[gi]) + size > cap[gi]) { Toast.makeText(a, "Крепость заполнена", Toast.LENGTH_SHORT).show(); return; }
                                JSONObject slot = null;
                                for (int i = 0; i < picked.length(); i++) if (picked.getJSONObject(i).getLong("id") == id) slot = picked.getJSONObject(i);
                                if (slot == null) { slot = new JSONObject().put("id", id).put("cnt", 0).put("lvl", levels.get(id)); picked.put(slot); }
                                slot.put("cnt", slot.getInt("cnt") + 1);
                                save.run(); render[0].run();
                                Toast.makeText(a, item.getString("name") + " ×" + slot.getInt("cnt"), Toast.LENGTH_SHORT).show();
                            } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                        });
                    } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                });
                android.widget.LinearLayout.LayoutParams bl = new android.widget.LinearLayout.LayoutParams(0, Math.round(H * 0.095f), 1); if (g > 0) bl.leftMargin = gap;
                buttons.addView(b, bl);
            }
            View clear = SandboxShop.button(a, "Очистить", SandboxShop.RED, H * 0.03f);
            clear.setOnClickListener(v -> { while (picked.length() > 0) picked.remove(0); save.run(); render[0].run(); });
            android.widget.LinearLayout.LayoutParams cl = new android.widget.LinearLayout.LayoutParams(0, Math.round(H * 0.095f), 0.8f); cl.leftMargin = gap;
            buttons.addView(clear, cl);
            android.widget.LinearLayout.LayoutParams bl = new android.widget.LinearLayout.LayoutParams(-1, -2); bl.topMargin = Math.round(H * 0.02f);
            body.addView(buttons, bl);
            render[0].run();
            android.app.Dialog d = SandboxShop.popup(a, "Крепость в защите", body, width);
            d.show();
        } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** defense-avatar.bin for the next attack: the save with its alliance units replaced by defense-cc.json; none when nothing is chosen. */
    private static void prepareDefenseAvatar(Activity a) throws Exception {
        File out = new File(a.getFilesDir(), "defense-avatar.bin");
        File chosen = new File(a.getFilesDir(), "defense-cc.json");
        if (!chosen.exists()) { if (out.exists() && !out.delete()) android.util.Log.w("OfflineTools", "defense-avatar.bin not removed"); return; }
        write(out, replaceAllianceUnits(read(new File(a.getFilesDir(), "save-avatar.bin")), defenseCastle(a)));
    }

    private static void prepareAttackAvatar(Activity a, JSONObject home) throws Exception {
        File out = new File(a.getFilesDir(), "attack-avatar.bin");
        byte[] b = read(new File(a.getFilesDir(), "save-avatar.bin"));
        List<long[]> friendly = loadoutUnits(home.optJSONObject("friendly_army_loadout"));
        List<long[]> main = loadoutUnits(home.optJSONObject("active_army_loadout"));
        List<Op> ops = parseOps(b);
        int at = -1;
        for (int i = 0; !friendly.isEmpty() && i + 2 < ops.size(); i++) {
            if (ops.get(i).method == 0x50 && ops.get(i).value == friendly.size() && ops.get(i + 1).method == 0x50 && ops.get(i + 1).value == friendly.get(0)[0] && ops.get(i + 2).method == 0x88 && ops.get(i + 2).value == friendly.get(0)[1]) { at = i; break; }
        }
        if (at < 0 || main.isEmpty()) { write(out, replaceFriendlyCastle(b, home)); return; }
        int startOff = ops.get(at).offset - 2, endIndex = at + 1 + 2 * friendly.size();
        int endOff = endIndex < ops.size() ? ops.get(endIndex).offset - 2 : b.length;
        ByteBuffer list = ByteBuffer.allocate(10 + main.size() * 20).order(ByteOrder.LITTLE_ENDIAN);
        list.putShort((short) 0x50).putLong(main.size());
        for (long[] u : main) { list.putShort((short) 0x50).putLong(u[0]); list.putShort((short) 0x88).putLong(u[1]); }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(b, 0, startOff); o.write(list.array(), 0, list.position()); o.write(b, endOff, b.length - endOff);
        byte[] r = o.toByteArray();
        int count = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(0) - (1 + 2 * friendly.size()) + (1 + 2 * main.size());
        ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).putInt(0, count);
        write(out, replaceFriendlyCastle(r, home));
    }

    /** Clan castle of the friendly-challenge army: (0x88 N, then N x (0x50 id, 0x88 count, 0x88 level)); replaced by the main army's castle. */
    private static byte[] replaceFriendlyCastle(byte[] b, JSONObject home) throws Exception {
        JSONObject ml = home.optJSONObject("active_army_loadout");
        JSONArray to = ml == null ? null : ml.getJSONArray("slots_by_type").optJSONArray(3);
        if (to == null) return b;
        List<Op> ops = parseOps(b);
        // The list follows the friendly army's hero line-up (0x88 H, H x 0x50 hero id, 0x88 x); found by that anchor, so an empty
        // friendly castle (0x88 0) is filled too. The last match in the stream is the friendly army's.
        int at = -1, old = 0;
        for (int i = 0; i + 2 < ops.size(); i++) {
            Op h = ops.get(i);
            if (h.method != 0x88 || h.value < 1 || h.value > 8 || i + h.value + 2 >= ops.size()) continue;
            boolean heroes = true;
            for (int k = 1; k <= h.value && heroes; k++) { Op id = ops.get(i + k); heroes = id.method == 0x50 && id.value >= 28000000 && id.value < 29000000; }
            int c = i + (int) h.value + 2;
            if (!heroes || ops.get(c - 1).method != 0x88 || ops.get(c).method != 0x88 || ops.get(c).value < 0 || ops.get(c).value > 30 || c + 3 * ops.get(c).value >= ops.size()) continue;
            boolean triples = true;
            for (int k = 0; k < ops.get(c).value && triples; k++)
                triples = ops.get(c + 1 + 3 * k).method == 0x50 && ops.get(c + 2 + 3 * k).method == 0x88 && ops.get(c + 3 + 3 * k).method == 0x88;
            if (triples) { at = c; old = (int) ops.get(c).value; }
        }
        if (at < 0) return b;
        int startOff = ops.get(at).offset - 2, endIndex = at + 1 + 3 * old;
        int endOff = endIndex < ops.size() ? ops.get(endIndex).offset - 2 : b.length;
        ByteBuffer list = ByteBuffer.allocate(10 + to.length() * 30).order(ByteOrder.LITTLE_ENDIAN);
        list.putShort((short) 0x88).putLong(to.length());
        for (int i = 0; i < to.length(); i++) {
            JSONObject u = to.getJSONObject(i);
            list.putShort((short) 0x50).putLong(u.getLong("id")); list.putShort((short) 0x88).putLong(u.getLong("m_count")); list.putShort((short) 0x88).putLong(u.optLong("m_level", 0));
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(b, 0, startOff); o.write(list.array(), 0, list.position()); o.write(b, endOff, b.length - endOff);
        byte[] r = o.toByteArray();
        ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).putInt(0, ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(0) - 3 * old + 3 * to.length());
        parseOps(r);  // refuse a broken rewrite
        return r;
    }

    /** attack-home.json: copy of the saved home with the chosen layout moved to slot 0 and the friendly-challenge army replaced by the main army. */
    private static void prepareAttackHome(Activity a, int slot) throws Exception {
        JSONObject home = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
        // The defending guardian is the one chosen in the home village (its active layout), whatever layout is attacked;
        // read before normalizeAttackLayout rewrites the slot 0 values.
        int active = home.optInt("active_layout", 0);
        JSONArray buildings = home.optJSONArray("buildings");
        java.util.Map<String, Object> guardians = new java.util.HashMap<>();
        for (int i = 0; buildings != null && i < buildings.length(); i++) {
            JSONObject b = buildings.getJSONObject(i);
            for (String field : new String[]{"m_currentGuardians", "m_currentGuardiansDraft"})
                if (b.has(field + "_" + active)) guardians.put(i + field, b.get(field + "_" + active));
        }
        normalizeAttackLayout(home, slot);
        for (int i = 0; buildings != null && i < buildings.length(); i++)
            for (String field : new String[]{"m_currentGuardians", "m_currentGuardiansDraft"})
                if (guardians.containsKey(i + field)) buildings.getJSONObject(i).put(field + "_0", guardians.get(i + field));
        JSONObject army = home.optJSONObject("active_army_loadout");
        if (army != null) {
            JSONObject friendly = new JSONObject(army.toString());
            JSONObject old = home.optJSONObject("friendly_army_loadout");
            friendly.put("m_type", old != null ? old.optInt("m_type", 3) : 3);
            home.put("friendly_army_loadout", friendly);
        }
        write(new File(a.getFilesDir(), "attack-home.json"), home.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject saved = new JSONObject(new String(read(new File(a.getFilesDir(), "save-home.json")), StandardCharsets.UTF_8));
        prepareAttackAvatar(a, saved);
        prepareDefenseAvatar(a);
        fillCastle(a, saved, false);  // castle preset of the army as set now, refilled after the battle
    }

    private static void normalizeAttackLayout(JSONObject home, int slot) throws Exception {
        if (slot != 0) {
            for (String key : LAYOUT_ARRAYS) {
                JSONArray items = home.optJSONArray(key);
                if (items == null) continue;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    int[] p = position(item, slot);
                    if (p != null) { item.put("x", p[0]); item.put("y", p[1]); }
                    else { item.put("x", -1); item.put("y", -1); }
                    String suffix = slot == 1 ? "_war" : slot == 6 ? "_chal" : slot == 7 ? "_arrw" : slot == 12 ? "_leg" : slot == 13 ? "_legb" : String.valueOf(slot);
                    if (item.has("aim_angle")) {
                        String aim = slot == 6 ? "aim_angle5_chal" : "aim_angle" + suffix;
                        int angle = item.has(aim) ? item.getInt(aim) : 0;
                        item.put("aim_angle", angle); item.put("aim_angle_draft", angle);
                    }
                    for (String weapon : new String[]{"", "_weapon"}) {
                        String active = "attack_mode" + weapon, selected = "attack_mode" + suffix + weapon;
                        if (item.has(active)) {
                            // Some defenses serialize a boolean switch, others a numeric mode.
                            // Copy the JSON value without coercion (false is not an integer).
                            Object mode = item.has(selected) ? item.get(selected)
                                    : item.get(active) instanceof Boolean ? Boolean.FALSE : Integer.valueOf(0);
                            item.put(active, mode); item.put("attack_mode_draft" + weapon, mode);
                        }
                    }
                    // Defensive heroes, guardians and the Crafting Station's defense are assigned per layout as well.
                    for (String field : new String[]{"m_defendingHero", "m_defendingHeroDraft", "m_currentGuardians", "m_currentGuardiansDraft", "m_currentArchetype", "m_currentArchetypeDraft"}) {
                        String selected = field + "_" + slot;
                        if (item.has(selected)) item.put(field + "_0", item.get(selected));
                        else item.remove(field + "_0");
                    }
                }
            }
        }
        // All objects above now describe slot 0. Do not let the original war/home
        // selection choose a different coordinate/defender array in the attack.
        home.put("active_layout", 0); home.put("war_layout", 0); home.put("selected_defensive_layout", 0);
    }


    // ---- hero equipment levels (avatar ops: owned list = count N, then N x (0x50 equipment id 900000xx, 0x88 level)) ----

    private static JSONArray equipmentCatalog(Activity a) throws Exception {
        try (java.io.InputStream in = a.getAssets().open("equipment.json")) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return new JSONArray(new String(o.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    /** Index of the count op of the owned-equipment list, or -1. */
    private static int ownedEquipmentList(List<Op> ops) {
        for (int i = 0; i + 2 < ops.size(); i++) {
            Op c = ops.get(i);
            if (c.method != 0x88 || c.value < 1 || c.value > 200 || i + 2 * c.value >= ops.size()) continue;
            boolean ok = true;
            for (int k = 0; k < c.value && ok; k++) {
                Op id = ops.get(i + 1 + 2 * k), lvl = ops.get(i + 2 + 2 * k);
                ok = id.method == 0x50 && id.value >= 90000000 && id.value < 90001000 && lvl.method == 0x88;
            }
            if (ok) return i;
        }
        return -1;
    }

    private static java.util.Map<Long, Long> ownedEquipment(Activity a) throws Exception {
        List<Op> ops = parseOps(read(new File(a.getFilesDir(), "save-avatar.bin")));
        java.util.Map<Long, Long> owned = new java.util.LinkedHashMap<>();
        int at = ownedEquipmentList(ops);
        for (int k = 0; at >= 0 && k < ops.get(at).value; k++) owned.put(ops.get(at + 1 + 2 * k).value, ops.get(at + 2 + 2 * k).value);
        return owned;
    }

    /** Rewrite the owned-equipment list with the given levels (adds items that were not owned). */
    private static String writeEquipment(Activity a, java.util.Map<Long, Long> levels) throws Exception {
        JSONArray catalog = equipmentCatalog(a);
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.getJSONObject(i);
            long id = item.getLong("id");
            Long value = levels.get(id);
            int max = item.getInt("max");
            // The previous editor could store max itself, showing max+1 in game.
            // Repair only that impossible boundary; keep every valid stored index.
            if (value != null && value == max) levels.put(id, (long) max - 1);
            else if (value != null && (value < 0 || value >= max))
                throw new Exception("Недопустимый уровень: " + item.getString("name"));
        }
        File f = new File(a.getFilesDir(), "save-avatar.bin");
        byte[] b = read(f);
        List<Op> ops = parseOps(b);
        int at = ownedEquipmentList(ops);
        if (at < 0) throw new Exception("список снаряжения не найден в сохранении");
        int oldCount = (int) ops.get(at).value;
        int startOff = ops.get(at).offset - 2, endIndex = at + 1 + 2 * oldCount;
        int endOff = endIndex < ops.size() ? ops.get(endIndex).offset - 2 : b.length;
        ByteBuffer list = ByteBuffer.allocate(10 + levels.size() * 20).order(ByteOrder.LITTLE_ENDIAN);
        list.putShort((short) 0x88).putLong(levels.size());
        for (java.util.Map.Entry<Long, Long> e : levels.entrySet()) { list.putShort((short) 0x50).putLong(e.getKey()); list.putShort((short) 0x88).putLong(e.getValue()); }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(b, 0, startOff); o.write(list.array(), 0, list.position()); o.write(b, endOff, b.length - endOff);
        byte[] r = o.toByteArray();
        int count = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(0) - 2 * oldCount + 2 * levels.size();
        ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).putInt(0, count);
        parseOps(r); // Refuse an invalid rewrite before replacing the last usable save.
        write(f, r);
        return "Снаряжение обновлено";
    }

    /**
     * Heroes window laid out like the game's hero hall: a row of the unlocked heroes (portrait, level), the selected hero large with
     * its level controls (applied in the running game), and the hero's equipment as icon tiles (levels saved through an in-place reload).
     */
    private static void heroWindow(Activity a) {
        try {
            int H = SandboxShop.screenH(a), W = SandboxShop.screenW(a);
            JSONArray catalog = unitCatalog(a);
            List<Op> ops = parseOps(read(new File(a.getFilesDir(), "save-avatar.bin")));
            int at = findLevelList(ops, unitLimits(catalog, "heroes"));
            java.util.Map<Long, Long> levels = new java.util.HashMap<>();
            for (int k = 0; k < ops.get(at).value; k++) levels.put(ops.get(at + 1 + 2 * k).value, ops.get(at + 2 + 2 * k).value);
            List<JSONObject> heroes = new ArrayList<>();
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject item = catalog.getJSONObject(i);
                if ("heroes".equals(item.getString("group")) && levels.containsKey(item.getLong("id"))) heroes.add(item);
            }
            if (heroes.isEmpty()) throw new IllegalArgumentException("Нет открытых героев");
            JSONArray equipment = equipmentCatalog(a);
            java.util.Map<Long, Long> owned = ownedEquipment(a);
            int[] selected = {0};
            android.widget.LinearLayout body = new android.widget.LinearLayout(a); body.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout heroRow = new android.widget.LinearLayout(a); heroRow.setGravity(Gravity.CENTER);
            body.addView(heroRow, new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.2f)));
            android.widget.LinearLayout detail = new android.widget.LinearLayout(a);
            android.widget.LinearLayout.LayoutParams dl = new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.5f)); dl.topMargin = Math.round(H * 0.02f);
            body.addView(detail, dl);
            android.app.Dialog[] dialog = new android.app.Dialog[1];
            Runnable[] render = new Runnable[1];
            render[0] = () -> {
                try {
                    heroRow.removeAllViews();
                    int tile = Math.round(H * 0.2f);
                    for (int h = 0; h < heroes.size(); h++) {
                        JSONObject hero = heroes.get(h); final int index = h;
                        int level = (int) (long) levels.get(hero.getLong("id")) + 1, max = hero.getInt("max");
                        View t = SandboxShop.tile(a, hero.getString("name"), level == max ? "МАКС" : level + "/" + max, level == max, SandboxShop.icon(a, hero.getLong("id")), Math.round(tile * 0.95f), tile);
                        t.setAlpha(h == selected[0] ? 1f : 0.55f);
                        t.setOnClickListener(v -> { selected[0] = index; render[0].run(); });
                        android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(Math.round(tile * 0.95f), tile); if (h > 0) tl.leftMargin = Math.round(H * 0.016f);
                        heroRow.addView(t, tl);
                    }
                    detail.removeAllViews();
                    JSONObject hero = heroes.get(selected[0]);
                    int max = hero.getInt("max"); int[] level = {(int) (long) levels.get(hero.getLong("id")) + 1};
                    // Left: the hero large with level controls.
                    android.widget.LinearLayout left = new android.widget.LinearLayout(a); left.setOrientation(android.widget.LinearLayout.VERTICAL); left.setGravity(Gravity.CENTER_HORIZONTAL);
                    left.setBackground(new SandboxShop.CardDrawable(H * 0.02f, false));
                    left.setPadding(Math.round(H * 0.02f), Math.round(H * 0.015f), Math.round(H * 0.02f), Math.round(H * 0.02f));
                    left.addView(new SandboxShop.OutlineText(a, hero.getString("name"), H * 0.04f, 0xFFFFFDE6, android.text.Layout.Alignment.ALIGN_CENTER), new android.widget.LinearLayout.LayoutParams(-1, -2));
                    android.widget.ImageView portrait = new android.widget.ImageView(a); portrait.setImageBitmap(SandboxShop.icon(a, hero.getLong("id"))); portrait.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    left.addView(portrait, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
                    android.widget.LinearLayout controls = new android.widget.LinearLayout(a); controls.setGravity(Gravity.CENTER);
                    int step = Math.round(H * 0.085f);
                    View minus = SandboxShop.button(a, "−", SandboxShop.BLUE, H * 0.045f), plus = SandboxShop.button(a, "+", SandboxShop.BLUE, H * 0.045f);
                    android.widget.TextView number = new android.widget.TextView(a);
                    number.setTypeface(font(a)); number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, H * 0.045f); number.setTextColor(Color.WHITE); number.setGravity(Gravity.CENTER);
                    number.setShadowLayer(5, 0, 3, Color.BLACK); number.setBackground(box(0xFF2F2D26, 0xFF0F0F0F, 18));
                    Runnable show = () -> number.setText(level[0] + " / " + max);
                    show.run();
                    minus.setOnClickListener(v -> { level[0] = Math.max(1, level[0] - 1); show.run(); });
                    plus.setOnClickListener(v -> { level[0] = Math.min(max, level[0] + 1); show.run(); });
                    controls.addView(minus, new android.widget.LinearLayout.LayoutParams(step, step));
                    android.widget.LinearLayout.LayoutParams nl = new android.widget.LinearLayout.LayoutParams(0, step, 1); nl.setMargins(Math.round(H * 0.015f), 0, Math.round(H * 0.015f), 0);
                    controls.addView(number, nl);
                    controls.addView(plus, new android.widget.LinearLayout.LayoutParams(step, step));
                    android.widget.LinearLayout.LayoutParams cl = new android.widget.LinearLayout.LayoutParams(-1, -2); cl.topMargin = Math.round(H * 0.01f);
                    left.addView(controls, cl);
                    android.widget.LinearLayout actions = new android.widget.LinearLayout(a);
                    View top = SandboxShop.button(a, "Максимум", SandboxShop.GRAY, H * 0.03f), save = SandboxShop.button(a, "Сохранить", SandboxShop.GREEN, H * 0.03f);
                    top.setOnClickListener(v -> { level[0] = max; show.run(); });
                    save.setOnClickListener(v -> {
                        liveUnitLevel(a, hero, level[0]);
                        try { levels.put(hero.getLong("id"), (long) level[0] - 1); } catch (Exception ignored) {}
                        render[0].run();
                    });
                    actions.addView(top, new android.widget.LinearLayout.LayoutParams(0, Math.round(H * 0.08f), 1));
                    android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(0, Math.round(H * 0.08f), 1); sl.leftMargin = Math.round(H * 0.012f);
                    actions.addView(save, sl);
                    android.widget.LinearLayout.LayoutParams al = new android.widget.LinearLayout.LayoutParams(-1, -2); al.topMargin = Math.round(H * 0.012f);
                    left.addView(actions, al);
                    detail.addView(left, new android.widget.LinearLayout.LayoutParams(Math.round(W * 0.26f), -1));
                    // Right: the hero's equipment.
                    android.widget.LinearLayout right = new android.widget.LinearLayout(a); right.setOrientation(android.widget.LinearLayout.VERTICAL);
                    right.addView(SandboxShop.text(a, "Снаряжение", H * 0.034f, 0xFF3A3A33), new android.widget.LinearLayout.LayoutParams(-1, -2));
                    android.widget.ScrollView scroll = new android.widget.ScrollView(a);
                    android.widget.LinearLayout grid = new android.widget.LinearLayout(a); grid.setOrientation(android.widget.LinearLayout.VERTICAL);
                    scroll.addView(grid);
                    right.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
                    int columns = 4, gap = Math.round(H * 0.012f);
                    int rightW = Math.round(W * 0.86f) - Math.round(H * 0.084f) - Math.round(W * 0.26f) - Math.round(H * 0.02f);
                    int tw = (rightW - (columns - 1) * gap) / columns, th = Math.round(tw * 1.05f);
                    android.widget.LinearLayout row = null; int inRow = 0;
                    for (int i = 0; i < equipment.length(); i++) {
                        JSONObject item = equipment.getJSONObject(i);
                        if (!item.getString("hero").equals(hero.getString("name"))) continue;
                        Long have = owned.get(item.getLong("id"));
                        int imax = item.getInt("max");
                        String badge = have == null ? "нет" : have + 1 == imax ? "МАКС" : (have + 1) + "/" + imax;
                        View t = SandboxShop.tile(a, item.getString("name"), badge, have != null && have + 1 == imax, SandboxShop.icon(a, item.getLong("id")), tw, th);
                        if (have == null) t.setAlpha(0.7f);
                        t.setOnClickListener(v -> { pickerClosed = () -> { try { Integer lv = appliedLevels.get("equip:" + item.getLong("id")); if (lv != null) owned.put(item.getLong("id"), (long) lv - 1); } catch (Exception ignored) {} render[0].run(); }; chooseEquipmentLevel(a, item); });
                        if (row == null || inRow == columns) {
                            row = new android.widget.LinearLayout(a); inRow = 0;
                            android.widget.LinearLayout.LayoutParams rl = new android.widget.LinearLayout.LayoutParams(-1, th); if (grid.getChildCount() > 0) rl.topMargin = gap;
                            grid.addView(row, rl);
                        }
                        android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(tw, th); if (inRow > 0) tl.leftMargin = gap;
                        row.addView(t, tl); inRow++;
                    }
                    if (grid.getChildCount() == 0) grid.addView(SandboxShop.text(a, "У этого героя нет снаряжения", H * 0.03f, 0xFF6A6A60));
                    android.widget.LinearLayout.LayoutParams rl = new android.widget.LinearLayout.LayoutParams(0, -1, 1); rl.leftMargin = Math.round(H * 0.02f);
                    detail.addView(right, rl);
                } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            };
            render[0].run();
            dialog[0] = SandboxShop.popup(a, "Зал героев", body, 0.86f);
            dialog[0].show();
        } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private static void chooseEquipmentHero(Activity a) {
        try {
            JSONArray cat = equipmentCatalog(a);
            List<String> heroes = new ArrayList<>();
            for (int i = 0; i < cat.length(); i++) { String h = cat.getJSONObject(i).getString("hero"); if (!heroes.contains(h)) heroes.add(h); }
            cocDialog(a, "Снаряжение", "Выберите героя", heroes.toArray(new String[0]), w -> chooseEquipmentItem(a, cat, heroes.get(w)));
        } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private static void chooseEquipmentItem(Activity a, JSONArray cat, String hero) {
        try {
            java.util.Map<Long, Long> owned = ownedEquipment(a);
            List<JSONObject> items = new ArrayList<>(); List<String> labels = new ArrayList<>();
            for (int i = 0; i < cat.length(); i++) {
                JSONObject it = cat.getJSONObject(i);
                if (!it.getString("hero").equals(hero)) continue;
                Long lvl = owned.get(it.getLong("id"));
                items.add(it); labels.add(it.getString("name") + " · " + (lvl == null ? "нет" : String.valueOf(lvl + 1)) + " / " + it.getInt("max"));
            }
            long[] ids = new long[items.size()]; for (int i = 0; i < ids.length; i++) ids[i] = items.get(i).getLong("id");
            searchDialog(a, hero, labels.toArray(new String[0]), ids, w -> chooseEquipmentLevel(a, items.get(w)));
        } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private static void chooseEquipmentLevel(Activity a, JSONObject item) {
        try {
            long id = item.getLong("id");
            Long saved = ownedEquipment(a).get(id);
            Integer applied = appliedLevels.get("equip:" + id);
            long current = applied != null ? applied : saved == null ? 0 : saved + 1;
            // Owned items: in the running game (the avatar's m_pCharacterItemUpgrade slot, probe.cpp); a new item is added through the save.
            showLevelPicker(a, item, current, selected -> {
                if (saved != null || applied != null) {
                    setLiveEquipmentLevel((int) id, selected - 1); appliedLevels.put("equip:" + id, selected);
                    try { Toast.makeText(a, item.getString("name") + ": уровень " + selected, Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                } else runEdit(a, () -> { java.util.Map<Long, Long> owned = ownedEquipment(a); owned.put(id, (long) selected - 1); return writeEquipment(a, owned); });
            });
        } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** Level picker in the game style: big level between round -/+ buttons, a slider, 1 / max shortcuts, green apply button. */
    private static void showLevelPicker(Activity a, JSONObject item, long previous, IntChoice onApply) {
        try {
            final int max = item.getInt("max");
            int H = SandboxShop.screenH(a);
            android.widget.LinearLayout body = new android.widget.LinearLayout(a); body.setOrientation(android.widget.LinearLayout.VERTICAL);
            body.addView(SandboxShop.text(a, previous == 0 ? "Не открыто" : "Сейчас: уровень " + previous + " из " + max, H * 0.03f, 0xFF3A3A33));
            int[] level = {(int) Math.max(1, Math.min(max, previous))};
            android.widget.LinearLayout row = new android.widget.LinearLayout(a); row.setGravity(Gravity.CENTER);
            int step = Math.round(H * 0.12f);
            View minus = SandboxShop.button(a, "−", SandboxShop.BLUE, H * 0.06f), plus = SandboxShop.button(a, "+", SandboxShop.BLUE, H * 0.06f);
            android.widget.TextView number = new android.widget.TextView(a);
            number.setTypeface(font(a)); number.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, H * 0.08f); number.setTextColor(Color.WHITE); number.setGravity(Gravity.CENTER);
            number.setShadowLayer(6, 0, 4, Color.BLACK); number.setBackground(box(0xFF2F2D26, 0xFF0F0F0F, 22));
            row.addView(minus, new android.widget.LinearLayout.LayoutParams(step, step));
            android.widget.LinearLayout.LayoutParams nl = new android.widget.LinearLayout.LayoutParams(Math.round(H * 0.3f), step); nl.setMargins(Math.round(H * 0.03f), 0, Math.round(H * 0.03f), 0);
            row.addView(number, nl);
            row.addView(plus, new android.widget.LinearLayout.LayoutParams(step, step));
            android.widget.LinearLayout.LayoutParams rowl = new android.widget.LinearLayout.LayoutParams(-1, -2); rowl.topMargin = Math.round(H * 0.025f);
            body.addView(row, rowl);
            android.widget.SeekBar slider = new android.widget.SeekBar(a);
            slider.setMax(max - 1);
            slider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF62B12C));
            slider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2F2D26));
            slider.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFF4C430));
            android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.08f)); sl.topMargin = Math.round(H * 0.015f);
            body.addView(slider, sl);
            java.util.function.IntConsumer select = value -> {
                level[0] = Math.max(1, Math.min(max, value));
                number.setText(String.valueOf(level[0])); slider.setProgress(level[0] - 1);
            };
            slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) { if (fromUser) select.accept(progress + 1); }
                public void onStartTrackingTouch(android.widget.SeekBar bar) {}
                public void onStopTrackingTouch(android.widget.SeekBar bar) {}
            });
            minus.setOnClickListener(v -> select.accept(level[0] - 1));
            plus.setOnClickListener(v -> select.accept(level[0] + 1));
            select.accept(level[0]);
            android.widget.LinearLayout quick = new android.widget.LinearLayout(a);
            View one = SandboxShop.button(a, "Уровень 1", SandboxShop.GRAY, H * 0.03f), top = SandboxShop.button(a, "Максимум", SandboxShop.GRAY, H * 0.03f);
            one.setOnClickListener(v -> select.accept(1)); top.setOnClickListener(v -> select.accept(max));
            quick.addView(one, new android.widget.LinearLayout.LayoutParams(0, Math.round(H * 0.09f), 1));
            android.widget.LinearLayout.LayoutParams ql = new android.widget.LinearLayout.LayoutParams(0, Math.round(H * 0.09f), 1); ql.leftMargin = Math.round(H * 0.02f);
            quick.addView(top, ql);
            android.widget.LinearLayout.LayoutParams qrl = new android.widget.LinearLayout.LayoutParams(-1, -2); qrl.topMargin = Math.round(H * 0.012f);
            body.addView(quick, qrl);
            if (!item.optString("note", "").isEmpty()) {
                android.widget.LinearLayout.LayoutParams notel = new android.widget.LinearLayout.LayoutParams(-1, -2); notel.topMargin = Math.round(H * 0.015f);
                body.addView(SandboxShop.text(a, item.optString("note"), H * 0.024f, 0xFF6A6A60), notel);
            }
            android.app.Dialog[] dialog = new android.app.Dialog[1];
            View apply = SandboxShop.button(a, "Применить", SandboxShop.GREEN, H * 0.042f);
            long[] current = {previous}; boolean[] applied = {false};
            apply.setOnClickListener(v -> {
                if (level[0] == current[0]) return;
                onApply.chosen(level[0]); current[0] = level[0]; applied[0] = true;
                ((android.widget.TextView) body.getChildAt(0)).setText("Сейчас: уровень " + level[0] + " из " + max);
            });
            android.widget.LinearLayout.LayoutParams al = new android.widget.LinearLayout.LayoutParams(-1, Math.round(H * 0.12f)); al.topMargin = Math.round(H * 0.025f);
            body.addView(apply, al);
            dialog[0] = SandboxShop.popup(a, item.getString("name"), body, 0.42f);
            android.app.Dialog list = currentList; Runnable reopen = currentListReopen;
            SandboxShop.onClose(dialog[0], () -> {
                if (pickerClosed != null) { Runnable r = pickerClosed; pickerClosed = null; if (applied[0]) r.run(); return; }
                if (applied[0] && reopen != null && list != null && list.isShowing()) { list.dismiss(); reopen.run(); }
            });
            dialog[0].show();
        } catch (Exception e) { Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    // Set by a window that rebuilds itself when the level picker it opened closes (the hero hall).
    private static Runnable pickerClosed;

    private static String setAllEquipmentMax(Activity a) throws Exception {
        JSONArray cat = equipmentCatalog(a);
        java.util.Map<Long, Long> owned = ownedEquipment(a);
        for (int i = 0; i < cat.length(); i++) owned.put(cat.getJSONObject(i).getLong("id"), (long) cat.getJSONObject(i).getInt("max") - 1);
        return writeEquipment(a, owned);
    }

    // ---- save-avatar.bin (avatar-ops format: u32 count; ops of u16 method + payload) ----

    private static final class Op { int method; int offset; long value; }

    private static List<Op> parseOps(byte[] b) {
        if (b.length < 4) throw new IllegalArgumentException("Сохранение аватара обрезано");
        List<Op> ops = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int count = buf.getInt();
        if (count < 1 || count > 100000 || count > (b.length - 4) / 7)
            throw new IllegalArgumentException("Некорректное число полей аватара");
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < 2) throw new IllegalArgumentException("Сохранение аватара обрезано");
            Op op = new Op();
            op.method = buf.getShort() & 0xffff;
            op.offset = buf.position();
            if (op.method == 0xa8) {
                if (buf.remaining() < 9) throw new IllegalArgumentException("Обрезанный LogicLong");
                buf.position(buf.position() + 9);
            } else if (op.method == 0x20 || op.method == 0x30 || op.method == 0x38) {
                if (buf.remaining() < 5) throw new IllegalArgumentException("Обрезанная строка");
                buf.get(); int len = buf.getInt();
                if (len < 0 || len > buf.remaining()) throw new IllegalArgumentException("Некорректная длина строки");
                buf.position(buf.position() + len);
            } else {
                if (op.method != 0x40 && op.method != 0x50 && op.method != 0x58 && op.method != 0x88 && op.method != 0x90 && op.method != 0xa0)
                    throw new IllegalArgumentException("Неизвестный тип поля аватара: " + op.method);
                if (buf.remaining() < 8) throw new IllegalArgumentException("Обрезанное числовое поле");
                op.value = buf.getLong();
            }
            ops.add(op);
        }
        if (buf.hasRemaining()) throw new IllegalArgumentException("Лишние данные в сохранении аватара");
        return ops;
    }

    private static void setValue(byte[] b, Op op, long value) {
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(op.offset, value);
    }

    /** which: 3000001 gold / 3000002 elixir / 3000003 dark elixir up to storage capacity; 0 = gems to 100 000. */
    /** Storage capacity of a resource: the first (id, amount) list of the avatar save holds the caps. */
    private static long storageCap(Activity a, long which) throws Exception {
        List<Op> ops = parseOps(read(new File(a.getFilesDir(), "save-avatar.bin")));
        for (int i = 0; i + 1 < ops.size(); i++)
            if (ops.get(i).method == 0x50 && ops.get(i).value == which && ops.get(i + 1).method == 0x88) return ops.get(i + 1).value;
        throw new Exception("ёмкость хранилища не найдена");
    }

    private static String topUpResources(Activity a, long which) throws Exception {
        File f = new File(a.getFilesDir(), "save-avatar.bin");
        byte[] b = read(f);
        List<Op> ops = parseOps(b);
        boolean changed = false;
        if (which == 0) {
            // Gems: the two ints after level and experience that follow the player name string.
            for (int i = 0; i + 5 < ops.size(); i++) {
                if (ops.get(i).method == 0x30 && ops.get(i + 1).method == 0x38 && ops.get(i + 4).method == 0x50 && ops.get(i + 5).method == 0x50) {
                    setValue(b, ops.get(i + 4), 100000); setValue(b, ops.get(i + 5), 100000); changed = true; break;
                }
            }
        } else {
            // Resource lists are (0x50 resource id, 0x88 amount) pairs; the first list holds storage caps, the second the current amounts.
            long cap = -1; int lists = 0;
            for (int i = 0; i + 1 < ops.size(); i++) {
                Op id = ops.get(i), amount = ops.get(i + 1);
                if (id.method != 0x50 || amount.method != 0x88) continue;
                if (id.value == 3000001) lists++;
                if (id.value != which) continue;
                if (lists == 1) cap = amount.value;
                else if (lists == 2 && cap > 0) { setValue(b, amount, cap); changed = true; break; }
            }
        }
        if (!changed) throw new Exception("не найдены поля ресурсов в сохранении");
        write(f, b);
        return which == 0 ? "Кристаллы: 100 000" : "Хранилище заполнено";
    }

    // ---- layout slots (save-home.json) ----

    /** Layout slot index N: slot 0 = "x"/"y", other slots "lNx" = [x, y]. */
    private static int[] position(JSONObject item, int slot) throws Exception {
        if (slot == 0) return item.has("x") ? new int[]{item.getInt("x"), item.getInt("y")} : null;
        JSONArray p = item.optJSONArray("l" + slot + "x");
        return p == null ? null : new int[]{p.getInt(0), p.getInt(1)};
    }

    // ---- files ----

    private static byte[] read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); }
        if (!tmp.renameTo(f)) throw new Exception("не удалось записать " + f.getName());
    }
}
