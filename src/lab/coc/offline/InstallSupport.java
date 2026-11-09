package lab.coc.offline;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** First-run support for the single-APK edition; never replaces existing saves. */
public final class InstallSupport {
    private static final String ORIGINAL = "com.supercell.clashofclans";
    private InstallSupport() {}

    public static boolean prepare(Activity activity) throws IOException {
        seedPortableState(activity);
        // The game's support library requires the original package to be installed (it checks it during start-up).
        try {
            activity.getPackageManager().getApplicationInfo(ORIGINAL, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            // show the next step instead of a start-up error
        }
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER);
        body.setPadding(40, 32, 40, 32); body.setBackgroundColor(0xff223243);
        TextView title = new TextView(activity); title.setText("Первый запуск CoC Sandbox");
        title.setTextSize(24); title.setTextColor(0xffffffff); title.setGravity(Gravity.CENTER); body.addView(title);
        TextView message = new TextView(activity);
        message.setText("Для запуска нужен установленный официальный Clash of Clans. Установите его из Google Play, затем вернитесь сюда и нажмите «Продолжить». Ресурсы песочницы уже находятся в этом приложении.");
        message.setTextSize(17); message.setTextColor(0xffeeeeee); message.setPadding(0, 24, 0, 24); body.addView(message);
        Button store = new Button(activity); store.setText("Открыть Google Play");
        store.setOnClickListener(v -> {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ORIGINAL))); }
            catch (ActivityNotFoundException noMarket) {
                try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + ORIGINAL))); }
                catch (ActivityNotFoundException noBrowser) { message.setText("Установите официальный Clash of Clans доступным способом, затем повторите запуск."); }
            }
        }); body.addView(store);
        Button retry = new Button(activity); retry.setText("Продолжить"); retry.setOnClickListener(v -> activity.recreate()); body.addView(retry);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true);
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -1)); activity.setContentView(scroll);
        return false;
    }

    private static void seedPortableState(Activity activity) throws IOException {
        if (activity.getAssets().list("portable-state").length == 0) return;
        File root = activity.getFilesDir(), pending = new File(root, ".portable-seed-pending");
        if (!pending.exists()) {
            if (new File(root, "save-home.json").exists() || new File(root, "save-avatar.bin").exists()) return;
            if (!pending.createNewFile()) throw new IOException("Не удалось подготовить начальное сохранение");
        }
        copyAssets(activity, "portable-state", root, root.getCanonicalPath());
        if (!pending.delete()) throw new IOException("Не удалось завершить начальное сохранение");
    }

    private static void copyAssets(Activity activity, String path, File target, String root) throws IOException {
        String canonical = target.getCanonicalPath();
        if (!canonical.equals(root) && !canonical.startsWith(root + File.separator)) throw new IOException("Недопустимый путь сохранения");
        String[] children = activity.getAssets().list(path);
        if (children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Не удалось создать папку сохранения");
            for (String child : children) copyAssets(activity, path + "/" + child, new File(target, child), root);
        } else {
            File temporary = new File(target.getPath() + ".portable-tmp");
            try (InputStream input = activity.getAssets().open(path); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = input.read(buffer)) > 0) output.write(buffer, 0, n);
                output.getFD().sync();
            }
            if (!temporary.renameTo(target)) throw new IOException("Не удалось записать начальное сохранение");
        }
    }
}
