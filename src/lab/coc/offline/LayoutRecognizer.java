package lab.coc.offline;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device layout recognition from battle screenshots (port of layout-recognition/recognize.py).
 * 1) YOLO (assets/recognizer/model.onnx, NMS built in) on 640 px tiles; the image is rescaled so the lattice step matches the training shots.
 * 2) Per image the isometric lattice is fitted: px = cx + a*u, py = cy + b*s (b = RATIO*a, u = x-y, s = x+y of the footprint centre).
 * 3) Images are merged by the integer (u, s) offset with most same-type matches; duplicates are dropped.
 * 4) Result: objects {name, x, y, w, conf} in the 44x44 building area (top-left tile, 3..46).
 */
final class LayoutRecognizer {
    static final class Det { String name; int w; double px, py, bw, conf; }
    static final class Obj { String name; int u, s, w; double conf; int x, y; }

    private static final int T = 640, STEP = 480, LO = 3, HI = 46, MAX_DU = 3;
    private static final double CONF = 0.35;
    private static LayoutRecognizer instance;

    final String[] names; final Map<String, Integer> size = new HashMap<>();
    final Map<String, Long> data = new HashMap<>(), hero = new HashMap<>();
    private final double trainA, ratio;
    private final OrtEnvironment env; private final OrtSession session;

    static synchronized LayoutRecognizer get(Context c) throws Exception {
        if (instance == null) instance = new LayoutRecognizer(c);
        return instance;
    }

    private LayoutRecognizer(Context c) throws Exception {
        JSONObject spec = new JSONObject(new String(asset(c, "recognizer/recognizer.json"), StandardCharsets.UTF_8));
        JSONArray n = spec.getJSONArray("names"); names = new String[n.length()];
        for (int i = 0; i < names.length; i++) names[i] = n.getString(i);
        JSONObject sz = spec.getJSONObject("size"), d = spec.getJSONObject("data"), h = spec.getJSONObject("hero");
        for (String k : names) size.put(k, sz.optInt(k, 1));
        for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) { String k = it.next(); data.put(k, d.getLong(k)); }
        for (java.util.Iterator<String> it = h.keys(); it.hasNext(); ) { String k = it.next(); hero.put(k, h.getLong(k)); }
        trainA = spec.getDouble("train_a"); ratio = spec.getDouble("ratio");
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(4);
        session = env.createSession(asset(c, "recognizer/model.onnx"), o);
    }

    private static byte[] asset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[1 << 16]; int k;
            while ((k = in.read(b)) > 0) out.write(b, 0, k);
            return out.toByteArray();
        }
    }

    /** Tiles of 640 px over the image resized by k; detections mapped back to original pixels; a detection is kept only in the tile whose core contains it. */
    private List<Det> detectScaled(Bitmap img, double k) throws Exception {
        Bitmap im = k == 1 ? img : Bitmap.createScaledBitmap(img, (int) Math.round(img.getWidth() * k), (int) Math.round(img.getHeight() * k), true);
        int W = im.getWidth(), H = im.getHeight();
        List<Det> out = new ArrayList<>();
        Bitmap tile = Bitmap.createBitmap(T, T, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(tile);
        int[] px = new int[T * T]; float[] chw = new float[3 * T * T];
        for (int ty : starts(H)) for (int tx : starts(W)) {
            canvas.drawColor(Color.rgb(114, 114, 114));  // letterbox grey where the image is smaller than a tile
            canvas.drawBitmap(im, -tx, -ty, null);
            tile.getPixels(px, 0, T, 0, 0, T, T);
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                chw[i] = ((c >> 16) & 255) / 255f; chw[T * T + i] = ((c >> 8) & 255) / 255f; chw[2 * T * T + i] = (c & 255) / 255f;
            }
            float[][] r;
            try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, T, T});
                 OrtSession.Result res = session.run(Collections.singletonMap("images", in))) {
                r = ((float[][][]) res.get(0).getValue())[0];
            }
            double core = (T - STEP) / 2.0;
            for (float[] b : r) {
                if (b[4] < CONF) continue;
                double cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
                if ((tx > 0 && cx < core) || (tx + T < W && cx > T - core)) continue;
                if ((ty > 0 && cy < core) || (ty + T < H && cy > T - core)) continue;
                Det d = new Det(); d.name = names[(int) b[5]]; d.w = size.get(d.name);
                d.px = (tx + cx) / k; d.py = (ty + cy) / k; d.bw = (b[2] - b[0]) / k; d.conf = b[4];
                out.add(d);
            }
        }
        return out;
    }

    private static List<Integer> starts(int size) {
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
        for (int v = 0; v <= Math.max(1, size - T); v += STEP) s.add(v);
        if (size > T) s.add(size - T);
        return new ArrayList<>(s);
    }

    /** Detect at the original size, then again at the scale where the lattice step matches the training shots; HUD rows are dropped. */
    List<Det> detect(Bitmap img) throws Exception {
        List<Det> d = detectScaled(img, 1);
        List<Double> widths = new ArrayList<>();
        for (Det x : d) if (x.w >= 2) widths.add(x.bw / (2 * x.w));
        if (!widths.isEmpty()) {
            double k = Math.max(0.4, Math.min(1.2, trainA / median(widths)));
            if (Math.abs(k - 1) > 0.08) d = detectScaled(img, k);
        }
        int H = img.getHeight(); List<Det> kept = new ArrayList<>();
        for (Det x : d) if (0.07 * H < x.py && x.py < 0.81 * H) kept.add(x);  // top banner and the troop card bar
        return kept;
    }

    private static double median(List<Double> v) {
        double[] a = new double[v.size()]; for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a); int n = a.length;
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }

    /** Grid search of the lattice step around the box-width estimate; lattice phase by circular mean; returns u, s per detection. */
    int[][] fitLattice(List<Det> dets) {
        List<Double> w = new ArrayList<>();
        for (Det d : dets) if (d.w >= 2) w.add(d.bw / (2 * d.w));
        if (w.isEmpty()) for (Det d : dets) w.add(d.bw / 2);
        double est = median(w), bestScore = Double.MAX_VALUE; int n = dets.size(); int[][] best = null;
        for (int i = 0; i <= 120; i++) {
            double a = est * (0.85 + 0.3 * i / 120), b = a * ratio;
            double sxr = 0, sxi = 0, syr = 0, syi = 0;
            for (Det d : dets) {
                sxr += Math.cos(2 * Math.PI * d.px / a); sxi += Math.sin(2 * Math.PI * d.px / a);
                syr += Math.cos(2 * Math.PI * d.py / b); syi += Math.sin(2 * Math.PI * d.py / b);
            }
            double cx = a / (2 * Math.PI) * Math.atan2(sxi, sxr), cy = b / (2 * Math.PI) * Math.atan2(syi, syr);
            int[] u = new int[n], s = new int[n]; double res = 0; int bad = 0;
            for (int j = 0; j < n; j++) {
                Det d = dets.get(j); double fu = (d.px - cx) / a, fs = (d.py - cy) / b;
                u[j] = (int) Math.rint(fu); s[j] = (int) Math.rint(fs);
                res += Math.hypot(fu - u[j], fs - s[j]);
                if (Math.floorMod(u[j] + s[j], 2) != d.w % 2) bad++;
            }
            if (bad * 2 > n) { for (int j = 0; j < n; j++) u[j] += 1; bad = n - bad; }  // the phase fixes the lattice only up to one step
            double score = res / n + 0.5 * bad / n;
            if (score < bestScore) { bestScore = score; best = new int[][]{u, s}; }
        }
        return best;
    }

    /** Align every view to the objects found so far by the integer offset with most same-type matches (rare types weigh more). */
    List<Obj> merge(List<List<Det>> views, List<int[][]> lattices) {
        List<Obj> objs = new ArrayList<>();
        for (int v = 0; v < views.size(); v++) {
            List<Det> dets = views.get(v); int[][] us = lattices.get(v);
            int du = 0, ds = 0;
            if (v > 0) {
                Map<String, List<int[]>> index = new HashMap<>();
                for (Obj o : objs) index.computeIfAbsent(o.name, k -> new ArrayList<>()).add(new int[]{o.u, o.s});
                LinkedHashMap<Long, Double> votes = new LinkedHashMap<>();
                for (int j = 0; j < dets.size(); j++) {
                    List<int[]> same = index.get(dets.get(j).name);
                    if (same == null) continue;
                    for (int[] p : same) {
                        int a = p[0] - us[0][j], b = p[1] - us[1][j];
                        // halves are panned vertically (u is the screen column), so only small column offsets are possible
                        if (Math.abs(a) <= MAX_DU && Math.floorMod(a + b, 2) == 0) votes.merge(((long) a << 32) | (b & 0xffffffffL), 1.0 / same.size(), Double::sum);
                    }
                }
                double top = -1;
                for (Map.Entry<Long, Double> e : votes.entrySet()) if (e.getValue() > top) { top = e.getValue(); du = (int) (e.getKey() >> 32); ds = (int) (long) e.getKey(); }
            }
            for (int j = 0; j < dets.size(); j++) {
                Det d = dets.get(j); Obj o = new Obj();
                o.name = d.name; o.u = us[0][j] + du; o.s = us[1][j] + ds; o.w = d.w; o.conf = d.conf; objs.add(o);
            }
        }
        objs.sort((p, q) -> Double.compare(q.conf, p.conf));
        List<Obj> kept = new ArrayList<>();
        for (Obj o : objs) {
            boolean dup = false;
            for (Obj k : kept) if (k.name.equals(o.name) && Math.abs(k.u - o.u) + Math.abs(k.s - o.s) <= 1) { dup = true; break; }
            if (!dup) kept.add(o);
        }
        return kept;
    }

    /** Footprint centres to top-left tiles, translated so that the most confident objects fit the building area (false detections
     *  outside the base would otherwise widen the layout beyond 44 tiles); ties keep the layout centred. Objects left outside are dropped. */
    static void toGrid(List<Obj> objs) {
        if (objs.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Obj o : objs) {
            o.x = Math.floorDiv(o.u + o.s - o.w, 2); o.y = Math.floorDiv(o.s - o.u - o.w, 2);
            minX = Math.min(minX, o.x); minY = Math.min(minY, o.y); maxX = Math.max(maxX, o.x + o.w); maxY = Math.max(maxY, o.y + o.w);
        }
        double cx = LO + (HI - LO + 1 - (maxX - minX)) / 2.0 - minX, cy = LO + (HI - LO + 1 - (maxY - minY)) / 2.0 - minY;
        int bestX = 0, bestY = 0; double best = -1;
        for (int ox = LO - maxX; ox <= HI + 1 - minX; ox++) for (int oy = LO - maxY; oy <= HI + 1 - minY; oy++) {
            double score = 0;
            for (Obj o : objs) if (inside(o.x + ox, o.y + oy, o.w)) score += o.conf;
            score -= 1e-6 * (Math.abs(ox - cx) + Math.abs(oy - cy));
            if (score > best) { best = score; bestX = ox; bestY = oy; }
        }
        for (Obj o : objs) { o.x += bestX; o.y += bestY; }
        objs.removeIf(o -> !inside(o.x, o.y, o.w));
    }

    static boolean inside(int x, int y, int w) { return x >= LO && y >= LO && x + w <= HI + 1 && y + w <= HI + 1; }

    interface Progress { void step(String text); }

    /** Screenshots in screen order (top ... bottom). The middle one is the merge base, so thin overlaps between the outer ones do not matter. */
    List<Obj> recognize(List<Bitmap> images, Progress progress) throws Exception {
        List<Integer> order = new ArrayList<>();
        int mid = (images.size() - 1) / 2; order.add(mid);
        for (int dist = 1; order.size() < images.size(); dist++) {
            if (mid - dist >= 0) order.add(mid - dist);
            if (mid + dist < images.size()) order.add(mid + dist);
        }
        List<List<Det>> views = new ArrayList<>(); List<int[][]> lattices = new ArrayList<>();
        for (int i : order) {
            progress.step("Скриншот " + (i + 1) + " из " + images.size() + "…");
            List<Det> d = detect(images.get(i));
            if (d.size() < 5) throw new Exception("на скриншоте " + (i + 1) + " почти ничего не найдено (" + d.size() + ")");
            views.add(d); lattices.add(fitLattice(d));
        }
        List<Obj> objs = merge(views, lattices); toGrid(objs);
        return objs;
    }

    /** files/import-layout.json for OfflineTools.importLayout: buildings by data id, hero flags by hero id (one flag per hero, best confidence). */
    JSONObject importSpec(List<Obj> objs, int slot) throws Exception {
        JSONArray buildings = new JSONArray(), heroes = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Obj o : objs) {  // sorted by confidence
            Long id = data.get(o.name), h = hero.get(o.name);
            if (id != null) buildings.put(new JSONObject().put("data", id).put("x", o.x).put("y", o.y).put("w", o.w));
            else if (h != null && seen.add(o.name)) heroes.put(new JSONObject().put("hero", h).put("x", o.x).put("y", o.y).put("w", 2));
        }
        return new JSONObject().put("slot", slot).put("objects", buildings).put("heroes", heroes);
    }
}
