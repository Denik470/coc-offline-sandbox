package lab.coc.offline;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.util.HashMap;
import java.util.Map;

/**
 * Sandbox menu drawn like the game's shop (popup "shop", measured on a 2800x1272 screen): black tab bar with icon tabs, light panel
 * (#EAEAE2) with an outlined title between two pieces of art, a dark rail (#2F2D26) of blue item cards with rays, and a green bar below.
 * Card art comes from the game's own UI atlases (assets/sandbox-shop, see build.py).
 */
final class SandboxShop {
    private SandboxShop() {}

    static final class Item {
        final String title, info, button, art; final Runnable action;
        Item(String title, String info, String button, String art, Runnable action) { this.title = title; this.info = info; this.button = button; this.art = art; this.action = action; }
    }

    static final class Tab {
        final String title, icon, left, right; final Item[] items;
        Tab(String title, String icon, String left, String right, Item... items) { this.title = title; this.icon = icon; this.left = left; this.right = right; this.items = items; }
    }

    private static int selectedTab;
    // Open sandbox windows, innermost last: each window stays until the player closes it; closeAll() is for actions that reload the
    // village or leave it (layout editor, replays, attacks).
    private static final java.util.List<Dialog> open = new java.util.ArrayList<>();
    static void track(Dialog d) { open.add(d); d.setOnDismissListener(x -> open.remove(d)); }
    /** Runs after the window closes (keeps it tracked). */
    static void onClose(Dialog d, Runnable after) { d.setOnDismissListener(x -> { open.remove(d); after.run(); }); }
    static void closeAll() { for (Dialog d : new java.util.ArrayList<>(open)) d.dismiss(); open.clear(); }
    private static Typeface typeface;
    private static final Map<String, Bitmap> art = new HashMap<>();

    static Typeface font(Context c) {
        if (typeface == null) {
            try { typeface = Typeface.createFromAsset(c.getAssets(), "font/Supercell-Magic_5.ttf"); } catch (Exception e) { typeface = Typeface.DEFAULT_BOLD; }
        }
        return typeface;
    }

    static Bitmap art(Context c, String name) {
        if (!art.containsKey(name)) {
            Bitmap b = null;
            try (java.io.InputStream in = c.getAssets().open("sandbox-shop/" + name + ".png")) { b = BitmapFactory.decodeStream(in); } catch (Exception ignored) {}
            art.put(name, b);
        }
        return art.get(name);
    }

    private static ImageView image(Context c, String name) {
        ImageView v = new ImageView(c);
        v.setImageBitmap(art(c, name)); v.setScaleType(ImageView.ScaleType.FIT_CENTER); v.setAdjustViewBounds(true);
        return v;
    }

    /** Opens the sandbox shop; footer is the text of the green bar. */
    static void show(Activity a, Tab[] tabs, String footer) {
        Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        int W = Math.max(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels);
        int H = Math.min(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels);
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xFFEAEAE2);
        int barH = Math.round(H * 0.115f), side = Math.round(W * 0.053f);
        View bar = new View(a); bar.setBackgroundColor(0xFF000000);
        root.addView(bar, new FrameLayout.LayoutParams(-1, barH, Gravity.TOP));

        LinearLayout tabRow = new LinearLayout(a);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, barH - Math.round(H * 0.012f) + 2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = Math.round(H * 0.012f);
        root.addView(tabRow, tl);

        int closeSize = Math.round(H * 0.088f);
        View close = new View(a); close.setBackground(new CloseDrawable());
        close.setOnClickListener(v -> d.dismiss()); pressable(close);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP | Gravity.END);
        cl.topMargin = Math.round(H * 0.014f); cl.rightMargin = Math.round(W * 0.033f);
        root.addView(close, cl);

        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER); header.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-1, Math.round(H * 0.19f), Gravity.TOP);
        hl.topMargin = barH;
        root.addView(header, hl);

        int railTop = Math.round(H * 0.315f), footTop = Math.round(H * 0.895f), footH = Math.round(H * 0.088f);
        FrameLayout rail = new FrameLayout(a); rail.setBackground(new RailDrawable(H * 0.014f));
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-1, footTop - railTop - Math.round(H * 0.012f), Gravity.TOP);
        rl.topMargin = railTop; rl.leftMargin = side; rl.rightMargin = side;
        root.addView(rail, rl);
        // Up to five cards per tab fit the rail; fewer are centred, as in the shop's shield tab.
        HorizontalScrollView scroll = new HorizontalScrollView(a); scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(true);
        rail.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout cards = new LinearLayout(a); cards.setGravity(Gravity.CENTER);
        int pad = Math.round(H * 0.022f), gap = Math.round(H * 0.024f);
        cards.setPadding(pad, pad, pad, pad);
        scroll.addView(cards, new FrameLayout.LayoutParams(-2, -1));

        FrameLayout foot = new FrameLayout(a); foot.setBackground(new GreenBarDrawable(H * 0.016f));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, footH, Gravity.TOP);
        fl.topMargin = footTop; fl.leftMargin = side; fl.rightMargin = side;
        root.addView(foot, fl);
        OutlineText footText = new OutlineText(a, footer, H * 0.034f, 0xFFFFFFFF, Layout.Alignment.ALIGN_CENTER);
        foot.addView(footText, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        int cardH = rl.height - 2 * pad, cardW = Math.min(Math.round(cardH * 0.72f), (W - 2 * side - 2 * pad - 4 * gap) / 5);
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            int current = Math.max(0, Math.min(tabs.length - 1, selectedTab));
            tabRow.removeAllViews();
            for (int i = 0; i < tabs.length; i++) {
                final int index = i;
                boolean active = i == current;
                FrameLayout t = new FrameLayout(a);
                t.setBackground(new TabDrawable(active, H * 0.012f));
                ImageView icon = image(a, tabs[i].icon);
                int inset = Math.round(H * 0.006f);
                FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-1, -1); il.setMargins(inset, inset, inset, active ? inset + 2 : inset);
                t.addView(icon, il);
                if (!active) { t.setOnClickListener(v -> { selectedTab = index; render[0].run(); }); pressable(t); }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(H * 0.15f), -1);
                lp.setMargins(Math.round(H * 0.004f), active ? 0 : Math.round(H * 0.008f), Math.round(H * 0.004f), 0);
                tabRow.addView(t, lp);
            }
            Tab tab = tabs[current];
            header.removeAllViews();
            int artH = Math.round(H * 0.16f);
            header.addView(image(a, tab.left), new LinearLayout.LayoutParams(-2, artH));
            OutlineText title = new OutlineText(a, tab.title, H * 0.062f, 0xFFFFFFFF, Layout.Alignment.ALIGN_CENTER);
            LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(-2, -2); ttl.setMargins(Math.round(H * 0.04f), 0, Math.round(H * 0.04f), 0);
            header.addView(title, ttl);
            header.addView(image(a, tab.right), new LinearLayout.LayoutParams(-2, artH));
            cards.removeAllViews();
            for (int i = 0; i < tab.items.length; i++) {
                Item item = tab.items[i];
                View card = card(a, item, cardW, cardH);
                card.setOnClickListener(v -> item.action.run());  // the shop stays open below what the card opens
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                if (i > 0) lp.leftMargin = gap;
                cards.addView(card, lp);
            }
            scroll.scrollTo(0, 0);
        };
        render[0].run();

        d.setContentView(root);
        track(d);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(0xFFEAEAE2));
        d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        d.getWindow().getDecorView().setSystemUiVisibility(5894);
        d.show();
    }

    /** One blue shop card: outlined title and a short description top-left, art in the middle over rays, dark button strip with its label at the bottom. */
    static View card(Context c, Item item, int w, int h) {
        FrameLayout card = new FrameLayout(c);
        card.setBackground(new CardDrawable(w * 0.045f, true));
        int m = Math.round(w * 0.06f);
        LinearLayout text = new LinearLayout(c); text.setOrientation(LinearLayout.VERTICAL);
        text.addView(new OutlineText(c, item.title, w * 0.085f, 0xFFFFFDE6, Layout.Alignment.ALIGN_NORMAL), new LinearLayout.LayoutParams(-1, -2));
        text.addView(new OutlineText(c, item.info, w * 0.056f, 0xFFFFFFFF, Layout.Alignment.ALIGN_NORMAL), new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); tl.setMargins(m, Math.round(h * 0.04f), m, 0);
        card.addView(text, tl);
        ImageView img = image(c, item.art);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(Math.round(w * 0.84f), Math.round(h * 0.45f), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        il.topMargin = Math.round(h * 0.315f);
        card.addView(img, il);
        OutlineText button = new OutlineText(c, item.button, w * 0.092f, 0xFFFFFFFF, Layout.Alignment.ALIGN_CENTER);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); bl.topMargin = Math.round(h * 0.835f);
        card.addView(button, bl);
        text.bringToFront();  // a two-line title stays readable over large art
        pressable(card);
        return card;
    }

    static final int GREEN = 0, BLUE = 1, RED = 2, GRAY = 3;

    /** Game button: black rim, coloured gradient body over a darker lip, light upper half, outlined label. */
    static View button(Context c, String text, int kind, float textPx) {
        FrameLayout b = new FrameLayout(c);
        b.setBackground(new ButtonDrawable(kind, textPx * 0.5f));
        OutlineText t = new OutlineText(c, text, textPx, 0xFFFFFFFF, Layout.Alignment.ALIGN_CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER); lp.setMargins(Math.round(textPx * 0.4f), 0, Math.round(textPx * 0.4f), Math.round(textPx * 0.12f));
        b.addView(t, lp);
        pressable(b);
        return b;
    }

    /** Dark body text of the game's popups (no outline). */
    static View text(Context c, String s, float px, int color) {
        android.widget.TextView t = new android.widget.TextView(c);
        t.setText(s); t.setTypeface(font(c)); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px); t.setTextColor(color); t.setGravity(Gravity.CENTER);
        return t;
    }

    static int screenH(Context c) { return Math.min(c.getResources().getDisplayMetrics().widthPixels, c.getResources().getDisplayMetrics().heightPixels); }
    static int screenW(Context c) { return Math.max(c.getResources().getDisplayMetrics().widthPixels, c.getResources().getDisplayMetrics().heightPixels); }

    /** Game popup: black rim, light panel, outlined title on a grey band, red close button in the corner; content below the title. */
    static Dialog popup(Activity a, String title, View content, float widthFraction) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        int H = screenH(a), W = screenW(a);
        FrameLayout root = new FrameLayout(a);
        LinearLayout panel = new LinearLayout(a); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new PanelDrawable(H * 0.03f, H * 0.11f));
        OutlineText head = new OutlineText(a, title, H * 0.045f, 0xFFFFFFFF, Layout.Alignment.ALIGN_CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, Math.round(H * 0.11f)); hl.setMargins(Math.round(H * 0.11f), 0, Math.round(H * 0.11f), 0);
        FrameLayout headBox = new FrameLayout(a); headBox.addView(head, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        panel.addView(headBox, hl);
        int m = Math.round(H * 0.03f);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.setMargins(m, m, m, m);
        panel.addView(content, cl);
        int edge = Math.round(H * 0.012f);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2); pl.setMargins(edge, edge, edge, edge);
        root.addView(panel, pl);
        int closeSize = Math.round(H * 0.08f);
        View close = new View(a); close.setBackground(new CloseDrawable()); close.setOnClickListener(v -> d.dismiss()); pressable(close);
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(closeSize, closeSize, Gravity.TOP | Gravity.END); xl.topMargin = Math.round(H * 0.026f); xl.rightMargin = Math.round(H * 0.026f);
        root.addView(close, xl);
        d.setContentView(root);
        track(d);
        d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        d.getWindow().setLayout(Math.round(W * widthFraction), ViewGroup.LayoutParams.WRAP_CONTENT);
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        d.getWindow().getDecorView().setSystemUiVisibility(5894);
        return d;
    }

    private static final android.util.LruCache<Long, Bitmap> icons = new android.util.LruCache<Long, Bitmap>(24 << 20) {
        @Override protected int sizeOf(Long key, Bitmap b) { return b.getByteCount(); }
    };

    /** The game's icon of a data id (assets/sandbox-icons, rendered from the SC files by tools/sc_icons.py), or null. */
    static Bitmap icon(Context c, long id) {
        Bitmap b = icons.get(id);
        if (b == null) {
            try (java.io.InputStream in = c.getAssets().open("sandbox-icons/" + id + ".webp")) { b = BitmapFactory.decodeStream(in); } catch (Exception ignored) {}
            if (b != null) icons.put(id, b);
        }
        return b;
    }

    /** Item tile of a list (unit, building, guardian, equipment): blue card, the game's icon, outlined name, level badge (gold at max level). */
    static View tile(Context c, String name, String level, boolean max, Bitmap icon, int w, int h) {
        FrameLayout t = new FrameLayout(c);
        t.setBackground(new CardDrawable(Math.min(w, h) * 0.1f, false));
        if (icon != null) {
            ImageView img = new ImageView(c); img.setImageBitmap(icon); img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(Math.round(w * 0.8f), Math.round(h * 0.5f), Gravity.TOP | Gravity.CENTER_HORIZONTAL); il.topMargin = Math.round(h * 0.22f);
            t.addView(img, il);
        }
        OutlineText n = new OutlineText(c, name, Math.min(h * 0.085f, w * 0.085f), 0xFFFFFDE6, Layout.Alignment.ALIGN_CENTER);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); nl.setMargins(Math.round(w * 0.04f), Math.round(h * 0.035f), Math.round(w * 0.04f), 0);
        t.addView(n, nl);
        if (level != null) {
            FrameLayout badge = new FrameLayout(c);
            badge.setBackground(new ButtonDrawable(max ? GOLD : DARK, h * 0.06f));
            badge.addView(new OutlineText(c, level, h * 0.085f, 0xFFFFFFFF, Layout.Alignment.ALIGN_CENTER), new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Math.round(w * 0.66f), Math.round(h * 0.17f), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); bl.bottomMargin = Math.round(h * 0.04f);
            t.addView(badge, bl);
        }
        pressable(t);
        return t;
    }
    private static final int GOLD = 4, DARK = 5;

    /** Shrinks a view slightly while it is held, like the game's buttons. */
    static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { view.setScaleX(0.95f); view.setScaleY(0.95f); }
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { view.setScaleX(1f); view.setScaleY(1f); }
            return false;
        });
    }

    static View hudButton(Context c, String label, String icon) { return hudButton(c, label, icon, false); }

    /** Home HUD button: beige framed square (small side buttons) or the big yellow one of the bottom corner, with art and an outlined label. */
    static View hudButton(Context c, String label, String icon, boolean yellow) {
        View b = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight(), r = w * 0.13f, e = w * 0.028f;
                p.setShader(null); p.setColor(0xFF0E0E0E); canvas.drawRoundRect(0, 0, w, h, r, r, p);
                p.setColor(yellow ? 0xFFB8700C : 0xFF7F6958); canvas.drawRoundRect(e, e, w - e, h - e, r * 0.8f, r * 0.8f, p);
                if (yellow) p.setShader(new RadialGradient(w / 2, h * 0.42f, w * 0.62f, new int[]{0xFFFFF27E, 0xFFFFD43A, 0xFFFFB811}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                else p.setShader(new LinearGradient(0, e, 0, h, new int[]{0xFFFBF7E2, 0xFFDCD0A4, 0xFFD3C796, 0xFFC9B391}, new float[]{0f, 0.06f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(e, e, w - e, h - e * 2.4f, r * 0.8f, r * 0.8f, p); p.setShader(null);
                if (yellow) { p.setColor(0x40FFFFFF); canvas.drawRoundRect(e * 2, e * 1.6f, w - e * 2, h * 0.3f, r * 0.6f, r * 0.6f, p); }
                Bitmap bmp = art(getContext(), icon);
                p.setColor(0xFF000000);  // opaque: the highlight above left a translucent colour in the paint
                if (bmp != null) {
                    float bw = w * 0.80f, bh = h * 0.56f, s = Math.min(bw / bmp.getWidth(), bh / bmp.getHeight());
                    float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s, x = (w - dw) / 2, y = h * 0.08f + (bh - dh) / 2;
                    canvas.drawBitmap(bmp, null, new RectF(x, y, x + dw, y + dh), p);
                }
                t.setTypeface(font(getContext())); t.setTextAlign(Paint.Align.CENTER);
                float size = h * 0.19f; t.setTextSize(size);
                while (t.measureText(label) > w * 0.95f && size > 8) { size -= 1; t.setTextSize(size); }
                float y = h * 0.86f;
                t.setStyle(Paint.Style.STROKE); t.setStrokeWidth(size * 0.22f); t.setStrokeJoin(Paint.Join.ROUND); t.setColor(0xFF000000);
                canvas.drawText(label, w / 2, y + size * 0.07f, t); canvas.drawText(label, w / 2, y, t);
                t.setStyle(Paint.Style.FILL); t.setColor(0xFFFFFFFF); canvas.drawText(label, w / 2, y, t);
                if (isPressed()) { p.setColor(0x33000000); canvas.drawRoundRect(0, 0, w, h, r, r, p); }
            }
            @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
        };
        b.setClickable(true);
        pressable(b);
        return b;
    }

    /** Game-style text: Supercell-Magic, black outline and a small drop shadow, wrapped to the view width. */
    static final class OutlineText extends View {
        private final TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG), stroke = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final String text; private final Layout.Alignment align;
        private StaticLayout fillLayout, strokeLayout; private int laidOut = -1;
        private final float pad;

        OutlineText(Context c, String text, float sizePx, int color, Layout.Alignment align) {
            super(c);
            this.text = text; this.align = align;
            for (TextPaint p : new TextPaint[]{fill, stroke}) { p.setTypeface(font(c)); p.setTextSize(sizePx); }
            fill.setColor(color);
            stroke.setColor(0xFF000000); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(sizePx * 0.2f); stroke.setStrokeJoin(Paint.Join.ROUND);
            pad = sizePx * 0.14f;
        }

        private void layout(int width) {
            if (width == laidOut) return;
            laidOut = width;
            int w = Math.max(1, width - Math.round(2 * pad));
            // Words are never broken: a word wider than the line shrinks the text until it fits.
            float widest = 0;
            for (String word : text.split("\\s+")) widest = Math.max(widest, stroke.measureText(word));
            if (widest > w) {
                float size = stroke.getTextSize() * w / widest * 0.98f;
                fill.setTextSize(size); stroke.setTextSize(size); stroke.setStrokeWidth(size * 0.2f);
            }
            fillLayout = StaticLayout.Builder.obtain(text, 0, text.length(), fill, w).setAlignment(align).setLineSpacing(0, 0.86f).setIncludePad(false).build();
            strokeLayout = StaticLayout.Builder.obtain(text, 0, text.length(), stroke, w).setAlignment(align).setLineSpacing(0, 0.86f).setIncludePad(false).build();
        }

        @Override protected void onMeasure(int ws, int hs) {
            int width = MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? Math.round(StaticLayout.getDesiredWidth(text, stroke) + 2 * pad + 1)
                : MeasureSpec.getMode(ws) == MeasureSpec.AT_MOST ? Math.min(MeasureSpec.getSize(ws), Math.round(StaticLayout.getDesiredWidth(text, stroke) + 2 * pad + 1))
                : MeasureSpec.getSize(ws);
            layout(width);
            setMeasuredDimension(width, Math.round(fillLayout.getHeight() + 2 * pad + stroke.getTextSize() * 0.08f));
        }

        @Override protected void onDraw(Canvas c) {
            layout(getWidth());
            c.save(); c.translate(pad, pad + stroke.getTextSize() * 0.08f); strokeLayout.draw(c); c.restore();
            c.save(); c.translate(pad, pad); strokeLayout.draw(c); fillLayout.draw(c); c.restore();
        }
    }

    private abstract static class Shape extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Blue shop card: dark rim, vertical blue gradient, light rays and glow behind the art, optional dark button strip. */
    static final class CardDrawable extends Shape {
        private final float r; private final boolean button;
        CardDrawable(float radius, boolean button) { r = radius; this.button = button; }
        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds()); float w = b.width(), h = b.height(), e = Math.max(2, w * 0.012f);
            p.setShader(null); p.setColor(0x55000000); c.drawRoundRect(b.left, b.top + e, b.right, b.bottom + e, r, r, p);
            p.setColor(0xFF1C5A6A); c.drawRoundRect(b, r, r, p);
            RectF in = new RectF(b.left + e, b.top + e, b.right - e, b.bottom - e);
            p.setShader(new LinearGradient(0, in.top, 0, in.bottom, new int[]{0xFF4CC6E1, 0xFF66D1EB, 0xFF52C2DF, 0xFF3FA6C6}, new float[]{0f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(in, r * 0.85f, r * 0.85f, p); p.setShader(null);
            c.save();
            Path clip = new Path(); clip.addRoundRect(in, r * 0.85f, r * 0.85f, Path.Direction.CW); c.clipPath(clip);
            float cx = in.centerX(), cy = in.top + h * (button ? 0.5f : 0.55f), len = Math.max(w, h);
            p.setColor(0x14FFFFFF);
            for (int i = 0; i < 16; i++) {
                double a0 = Math.toRadians(i * 22.5), a1 = Math.toRadians(i * 22.5 + 11);
                Path ray = new Path(); ray.moveTo(cx, cy);
                ray.lineTo(cx + (float) Math.cos(a0) * len, cy + (float) Math.sin(a0) * len);
                ray.lineTo(cx + (float) Math.cos(a1) * len, cy + (float) Math.sin(a1) * len); ray.close();
                c.drawPath(ray, p);
            }
            p.setShader(new RadialGradient(cx, cy, w * 0.5f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, w * 0.5f, p); p.setShader(null);
            p.setColor(0x40FFFFFF); c.drawRect(in.left, in.top, in.right, in.top + e * 1.5f, p);
            if (button) {
                RectF bt = new RectF(in.left + w * 0.035f, in.top + h * 0.80f, in.right - w * 0.035f, in.bottom - w * 0.035f);
                p.setColor(0x33002A36); c.drawRoundRect(bt.left, bt.top - e, bt.right, bt.bottom, r * 0.6f, r * 0.6f, p);
                p.setColor(0xFF367686); c.drawRoundRect(bt, r * 0.6f, r * 0.6f, p);
                p.setColor(0x22FFFFFF); c.drawRect(bt.left + r * 0.6f, bt.bottom - e * 1.2f, bt.right - r * 0.6f, bt.bottom - e * 0.2f, p);
            }
            c.restore();
        }
    }

    /** Button body for {@link #button}: GREEN, BLUE, RED, GRAY, GOLD (max level badge), DARK (level badge). */
    static final class ButtonDrawable extends Shape {
        private static final int[] TOP = {0xFFB5EC62, 0xFF7FD3F7, 0xFFF76A5C, 0xFFF4F4EE, 0xFFFFE27A, 0xFF5B6E78};
        private static final int[] BOTTOM = {0xFF62B12C, 0xFF2F92D6, 0xFFC92A1E, 0xFFBDBDB3, 0xFFE0A21E, 0xFF2E3B42};
        private static final int[] LIP = {0xFF3D7A16, 0xFF1B5D8E, 0xFF7E140C, 0xFF85857C, 0xFF9A6410, 0xFF1A2328};
        private final int kind; private final float r;
        ButtonDrawable(int kind, float radius) { this.kind = kind; r = radius; }
        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds()); float e = Math.max(2, Math.min(b.width(), b.height()) * 0.05f);
            p.setShader(null); p.setColor(0xFF0F0F0F); c.drawRoundRect(b, r, r, p);
            p.setColor(LIP[kind]); c.drawRoundRect(b.left + e, b.top + e, b.right - e, b.bottom - e, r * 0.85f, r * 0.85f, p);
            RectF body = new RectF(b.left + e, b.top + e, b.right - e, b.bottom - e * 2.6f);
            p.setShader(new LinearGradient(0, body.top, 0, body.bottom, TOP[kind], BOTTOM[kind], Shader.TileMode.CLAMP));
            c.drawRoundRect(body, r * 0.85f, r * 0.85f, p); p.setShader(null);
            p.setColor(0x33FFFFFF); c.drawRoundRect(body.left + e, body.top + e * 0.6f, body.right - e, body.top + body.height() * 0.45f, r * 0.6f, r * 0.6f, p);
        }
    }

    /** Popup panel: black rim, grey title band, light body. */
    static final class PanelDrawable extends Shape {
        private final float r, band;
        PanelDrawable(float radius, float band) { r = radius; this.band = band; }
        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds()); float e = Math.max(3, r * 0.22f);
            p.setShader(null); p.setColor(0xFF0F0F0F); c.drawRoundRect(b, r, r, p);
            RectF in = new RectF(b.left + e, b.top + e, b.right - e, b.bottom - e);
            p.setColor(0xFFEAEAE2); c.drawRoundRect(in, r * 0.8f, r * 0.8f, p);
            c.save(); c.clipRect(in.left, in.top, in.right, b.top + band);
            p.setShader(new LinearGradient(0, in.top, 0, b.top + band, 0xFF6E6C64, 0xFF4A4943, Shader.TileMode.CLAMP));
            c.drawRoundRect(in, r * 0.8f, r * 0.8f, p); p.setShader(null); c.restore();
            p.setColor(0xFF2F2D26); c.drawRect(in.left, b.top + band - e * 0.6f, in.right, b.top + band, p);
        }
    }

    /** Dark rail the cards sit on. */
    static final class RailDrawable extends Shape {
        private final float r;
        RailDrawable(float radius) { r = radius; }
        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds()); float e = Math.max(2, b.height() * 0.008f);
            p.setColor(0xFF1E1D19); c.drawRoundRect(b, r, r, p);
            p.setColor(0xFF41413D); c.drawRoundRect(b.left + e, b.top + e, b.right - e, b.bottom - e, r, r, p);
            p.setColor(0xFF2F2D26); c.drawRoundRect(b.left + e, b.top + e * 3, b.right - e, b.bottom - e, r, r, p);
        }
    }

    /** Green bar under the rail. */
    static final class GreenBarDrawable extends Shape {
        private final float r;
        GreenBarDrawable(float radius) { r = radius; }
        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds()); float e = Math.max(2, b.height() * 0.05f);
            p.setShader(null); p.setColor(0xFF4C7A2C); c.drawRoundRect(b, r, r, p);
            p.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[]{0xFFB9DE9D, 0xFFA6D186, 0xFF82B655}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(b.left + e, b.top + e, b.right - e, b.bottom - e, r * 0.8f, r * 0.8f, p); p.setShader(null);
            p.setColor(0x55FFFFFF); c.drawRoundRect(b.left + e * 3, b.top + e * 1.6f, b.right - e * 3, b.top + b.height() * 0.42f, r * 0.6f, r * 0.6f, p);
        }
    }

    /** Icon tab of the top bar; the active one is light and joins the panel below. */
    static final class TabDrawable extends Shape {
        private final boolean active; private final float r;
        TabDrawable(boolean active, float radius) { this.active = active; r = radius; }
        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds()); float e = Math.max(2, b.width() * 0.02f);
            Path outer = top(b, r);
            p.setShader(null); p.setColor(active ? 0xFFBDBDB3 : 0xFF111111); c.drawPath(outer, p);
            RectF in = new RectF(b.left + e, b.top + e, b.right - e, active ? b.bottom + 1 : b.bottom);
            p.setShader(active ? new LinearGradient(0, in.top, 0, in.bottom, 0xFFFFFFFA, 0xFFEAEAE2, Shader.TileMode.CLAMP)
                : new LinearGradient(0, in.top, 0, in.bottom, 0xFF7A786E, 0xFF4A4943, Shader.TileMode.CLAMP));
            c.drawPath(top(in, r * 0.8f), p); p.setShader(null);
        }
        private static Path top(RectF b, float r) {
            Path path = new Path();
            path.addRoundRect(b, new float[]{r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
            return path;
        }
    }

    /** Red square close button with a white outlined cross. */
    static final class CloseDrawable extends Shape {
        @Override public void draw(Canvas c) {
            RectF b = new RectF(getBounds()); float w = b.width(), r = w * 0.18f, e = w * 0.05f;
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(0xFFEDE6D8); c.drawRoundRect(b, r, r, p);
            p.setColor(0xFF6B0F0A); c.drawRoundRect(b.left + e, b.top + e, b.right - e, b.bottom - e, r * 0.85f, r * 0.85f, p);
            p.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xFFF5584A, 0xFFC3241A, Shader.TileMode.CLAMP));
            c.drawRoundRect(b.left + e * 2, b.top + e * 2, b.right - e * 2, b.bottom - e * 2.4f, r * 0.7f, r * 0.7f, p); p.setShader(null);
            float cx = b.centerX(), cy = b.centerY(), s = w * 0.2f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFF3A0705); p.setStrokeWidth(w * 0.2f);
            c.drawLine(cx - s, cy - s, cx + s, cy + s, p); c.drawLine(cx + s, cy - s, cx - s, cy + s, p);
            p.setColor(0xFFFFFFFF); p.setStrokeWidth(w * 0.11f);
            c.drawLine(cx - s, cy - s, cx + s, cy + s, p); c.drawLine(cx + s, cy - s, cx - s, cy + s, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
