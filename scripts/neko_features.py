#!/usr/bin/env python3
"""Umbra features for the Nekogram-based Android client. Usage: neko_features.py <nekogram-dir>
Every edit asserts that it found its target."""
import pathlib, sys

root = pathlib.Path(sys.argv[1])


def edit(path, old, new):
    p = root / path
    s = p.read_text()
    assert s.count(old) == 1, f"{path}: expected exactly one match for {old[:70]!r}, got {s.count(old)}"
    p.write_text(s.replace(old, new))


java = root / "TMessagesProj/src/main/java/app/umbra"
java.mkdir(parents=True, exist_ok=True)

# ---- Keep chats you were removed from (kicked / banned) in the chat list with their saved messages ----
(java / "KeepKicked.java").write_text("""package app.umbra;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

/**
 * Telegram normally deletes a group from the chat list and wipes its saved messages when you are kicked.
 * Umbra keeps it. Only what is already stored on the phone can be read: the server sends nothing more
 * once you are out, and leaving a chat yourself still removes it as usual.
 */
public final class KeepKicked {
    private KeepKicked() {
    }

    public static boolean enabled() {
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("umbra", Context.MODE_PRIVATE);
        return p.getBoolean("keep_kicked", true);
    }

    public static void setEnabled(boolean value) {
        ApplicationLoader.applicationContext.getSharedPreferences("umbra", Context.MODE_PRIVATE).edit().putBoolean("keep_kicked", value).apply();
    }

    /** True when the chat must stay in the list although the account is no longer a member. */
    public static boolean keep(TLRPC.Chat chat) {
        if (chat == null || !enabled()) {
            return false;
        }
        if (chat.left) {
            return false;
        }
        return chat.kicked || chat instanceof TLRPC.TL_channelForbidden || chat instanceof TLRPC.TL_chatForbidden;
    }
}
""")
mc = "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java"
edit(mc, "} else if (ChatObject.isNotInChat(chat) && dialog != null && (promoDialog == null || promoDialog.id != dialog.id)) {\n                                deleteDialog(dialog.id, 0);",
         "} else if (ChatObject.isNotInChat(chat) && !app.umbra.KeepKicked.keep(chat) && dialog != null && (promoDialog == null || promoDialog.id != dialog.id)) {\n                                deleteDialog(dialog.id, 0);")
edit(mc, "                            TLRPC.Dialog dialog = dialogs_dict.get(-chat.id);\n                            if (dialog != null) {\n                                deleteDialog(dialog.id, 0);\n                            }",
         "                            TLRPC.Dialog dialog = dialogs_dict.get(-chat.id);\n                            if (dialog != null && !app.umbra.KeepKicked.keep(chat)) {\n                                deleteDialog(dialog.id, 0);\n                            }")

# ---- Poppies theme: falling pink poppies over the whole app; catch three for a bouquet. Off until switched on. ----
(java / "UmbraPoppies.java").write_text("""package app.umbra;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.ApplicationLoader;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/** Pink poppies fall over the whole app. Tap one to catch it; three caught fill the basket (a bouquet). */
public final class UmbraPoppies {
    private static final String PREFS = "umbra";
    private static WeakReference<Activity> current = new WeakReference<>(null);
    private static PoppyView view;

    private UmbraPoppies() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean("poppies", false);
    }

    /** Poppy opacity in percent, 20..100. */
    public static int opacity() {
        return Math.max(20, Math.min(100, prefs().getInt("poppy_opacity", 100)));
    }

    public static void setOpacity(int percent) {
        prefs().edit().putInt("poppy_opacity", percent).apply();
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean("poppies", enabled).apply();
        refresh();
    }

    /** Call from the main activity's onResume. */
    public static void attach(Activity activity) {
        current = new WeakReference<>(activity);
        refresh();
    }

    public static void refresh() {
        Activity activity = current.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        if (isEnabled()) {
            if (view != null && view.getParent() == content) {
                view.bringToFront();
                return;
            }
            if (view != null && view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            view = new PoppyView(activity);
            content.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (view != null) {
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            view = null;
        }
    }

    private static final class Flower {
        float x, y, speed, sway, phase, size, spin, age;
        boolean caught;
        float catchT;
        float fromX, fromY;
    }

    private static final class PoppyView extends View {
        private final Random random = new Random();
        private final ArrayList<Flower> flowers = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final RectF box = new RectF();
        private final Bitmap poppy;
        private final float density;
        private long last;
        private float untilNext = 0.4f;
        private int inBasket;
        private long bouquetShownUntil;
        private Bitmap edgeLeft;
        private Bitmap edgeRight;
        private int edgeFor;

        /** Two strips of poppies along the left and right edges of the screen (drawn once per size). */
        private void buildEdges(int w, int h) {
            edgeFor = w * 31 + h;
            int stripW = (int) (74 * density);
            edgeLeft = Bitmap.createBitmap(stripW, Math.max(1, h), Bitmap.Config.ARGB_8888);
            edgeRight = Bitmap.createBitmap(stripW, Math.max(1, h), Bitmap.Config.ARGB_8888);
            Canvas left = new Canvas(edgeLeft);
            Canvas right = new Canvas(edgeRight);
            Random r = new Random(7);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Matrix m = new Matrix();
            int count = Math.max(4, (int) (h / (110 * density)));
            for (int side = 0; side < 2; side++) {
                Canvas c = side == 0 ? left : right;
                for (int i = 0; i < count; i++) {
                    float size = (56 + r.nextFloat() * 40) * density;
                    float y = (i + 0.3f + r.nextFloat() * 0.5f) * h / count;
                    float x = side == 0 ? (-10 + r.nextFloat() * 30) * density : stripW - (-10 + r.nextFloat() * 30) * density;
                    float scale = size / poppy.getWidth();
                    m.reset();
                    m.postTranslate(-poppy.getWidth() / 2f, -poppy.getHeight() / 2f);
                    m.postScale(scale, scale);
                    m.postRotate(r.nextFloat() * 360f);
                    m.postTranslate(x, y);
                    c.drawBitmap(poppy, m, p);
                }
            }
        }

        PoppyView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            poppy = drawPoppy(96);
            boxPaint.setColor(0xEBFFE6EF);
            textPaint.setColor(0xFF9E1A54);
            textPaint.setTextSize(15 * density);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
            setClickable(false);
        }

        private static Bitmap drawPoppy(int size) {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bitmap);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float cx = size / 2f, cy = size / 2f;
            float pw = size * 0.46f, ph = size * 0.5f;
            for (int i = 0; i < 5; i++) {
                c.save();
                c.rotate(i * 72f, cx, cy);
                RectF petal = new RectF(cx - pw / 2, cy - ph * 0.92f, cx + pw / 2, cy - ph * 0.92f + ph);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFF8CB8);
                c.drawOval(petal, p);
                RectF inner = new RectF(petal.left + pw * 0.16f, petal.top + ph * 0.2f, petal.right - pw * 0.16f, petal.bottom - ph * 0.08f);
                p.setColor(0xE6FFB8D6);
                c.drawOval(inner, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f);
                p.setColor(0xB3EB5494);
                c.drawOval(petal, p);
                c.restore();
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFD9EB);
            c.drawCircle(cx, cy, size * 0.13f, p);
            p.setColor(0xFF331429);
            c.drawCircle(cx, cy, size * 0.07f, p);
            for (int i = 0; i < 10; i++) {
                double a = i * Math.PI * 2 / 10;
                c.drawCircle(cx + (float) Math.cos(a) * size * 0.11f, cy + (float) Math.sin(a) * size * 0.11f, 1.6f, p);
            }
            return bitmap;
        }

        private float basketCx() {
            return getWidth() - 74 * density;
        }

        private float basketCy() {
            return getHeight() - 150 * density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = System.nanoTime();
            float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1_000_000_000f);
            last = now;
            int w = getWidth(), h = getHeight();
            float op = opacity() / 100f;
            if (w > 0) {
                // The whole interface turns a little pink.
                canvas.drawColor(((int) (0x20 * op) << 24) | 0xFF6FA8);
                if (edgeLeft == null || edgeFor != w * 31 + h) {
                    buildEdges(w, h);
                }
                paint.setAlpha((int) (255 * 0.55f * op));
                canvas.drawBitmap(edgeLeft, 0, 0, paint);
                canvas.drawBitmap(edgeRight, w - edgeRight.getWidth(), 0, paint);
                untilNext -= dt;
                if (untilNext <= 0 && flowers.size() < 9) {
                    untilNext = 1.0f + random.nextFloat() * 1.2f;
                    Flower f = new Flower();
                    f.size = (44 + random.nextFloat() * 20) * density;
                    f.x = 30 * density + random.nextFloat() * Math.max(1, w - 60 * density);
                    f.y = -40 * density;
                    f.speed = (55 + random.nextFloat() * 55) * density;
                    f.sway = (12 + random.nextFloat() * 22) * density;
                    f.phase = random.nextFloat() * 6.28f;
                    f.spin = random.nextFloat() * 1.6f - 0.8f;
                    flowers.add(f);
                }
                for (Iterator<Flower> it = flowers.iterator(); it.hasNext(); ) {
                    Flower f = it.next();
                    f.age += dt;
                    if (f.caught) {
                        f.catchT += dt / 0.35f;
                        if (f.catchT >= 1f) {
                            it.remove();
                            continue;
                        }
                        float t = f.catchT * f.catchT;
                        float x = f.fromX + (basketCx() - f.fromX) * t;
                        float y = f.fromY + (basketCy() - f.fromY) * t;
                        drawFlower(canvas, x, y, f.size * (1f - 0.75f * f.catchT), f.age, f.spin, 1f - f.catchT);
                    } else {
                        f.y += f.speed * dt;
                        if (f.y > h + 50 * density) {
                            it.remove();
                            continue;
                        }
                        float x = f.x + (float) Math.sin(f.age * 1.3f + f.phase) * f.sway;
                        drawFlower(canvas, x, f.y, f.size, f.age, f.spin, 1f);
                    }
                }
                drawBasket(canvas);
            }
            postInvalidateOnAnimation();
        }

        private void drawFlower(Canvas canvas, float x, float y, float size, float age, float spin, float alpha) {
            float scale = size / poppy.getWidth();
            matrix.reset();
            matrix.postTranslate(-poppy.getWidth() / 2f, -poppy.getHeight() / 2f);
            matrix.postScale(scale, scale);
            matrix.postRotate((float) Math.toDegrees(Math.sin(age * 0.9f + spin) * 0.5f + spin * age * 0.2f));
            matrix.postTranslate(x, y);
            paint.setAlpha((int) (255 * Math.max(0f, alpha) * opacity() / 100f));
            canvas.drawBitmap(poppy, matrix, paint);
        }

        private void drawBasket(Canvas canvas) {
            int total = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("bouquets", 0);
            String text;
            if (System.currentTimeMillis() < bouquetShownUntil) {
                text = "\\uD83D\\uDC90 Букет собран! x" + total;
            } else {
                StringBuilder dots = new StringBuilder();
                for (int i = 0; i < 3; i++) {
                    dots.append(i < inBasket ? "\\uD83C\\uDF3A" : "\\u00B7").append(i < 2 ? " " : "");
                }
                text = "\\uD83E\\uDDFA " + dots + (total > 0 ? "  \\uD83D\\uDC90" + total : "");
            }
            float width = textPaint.measureText(text) + 28 * density;
            float cx = basketCx(), cy = basketCy();
            box.set(getWidth() - 12 * density - width, cy - 18 * density, getWidth() - 12 * density, cy + 18 * density);
            canvas.drawRoundRect(box, 18 * density, 18 * density, boxPaint);
            canvas.drawText(text, box.centerX(), box.centerY() + 5.5f * density, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // Only a tap on a flower is taken; anything else goes on to the app below.
            if (event.getAction() != MotionEvent.ACTION_DOWN) {
                return false;
            }
            float radius = 34 * density;
            for (int i = flowers.size() - 1; i >= 0; i--) {
                Flower f = flowers.get(i);
                if (f.caught) {
                    continue;
                }
                float x = f.x + (float) Math.sin(f.age * 1.3f + f.phase) * f.sway;
                float dx = event.getX() - x, dy = event.getY() - f.y;
                if (dx * dx + dy * dy <= radius * radius) {
                    f.caught = true;
                    f.fromX = x;
                    f.fromY = f.y;
                    f.catchT = 0f;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    inBasket++;
                    if (inBasket >= 3) {
                        inBasket = 0;
                        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                        p.edit().putInt("bouquets", p.getInt("bouquets", 0) + 1).apply();
                        bouquetShownUntil = System.currentTimeMillis() + 2000;
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            return super.dispatchTouchEvent(ev);
        }
    }
}
""")

launch = "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
settings = "TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings/NekoAppearanceSettingsActivity.java"

edit(launch, "    protected void onResume() {\n", "    protected void onResume() {\n        app.umbra.UmbraPoppies.attach(this);\n")
edit(settings, "    private final int strokeOnViewsRow = rowId++;\n",
     "    private final int strokeOnViewsRow = rowId++;\n"
     "    private final int umbraPoppiesRow = rowId++;\n"
     "    private final int umbraPoppyOpacityRow = rowId++;\n"
     "    private final int umbraKeepKickedRow = rowId++;\n")
edit(settings, "        items.add(UItem.asShadow(null));\n\n    }\n\n    @Override\n    protected void onItemClick",
     """        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Umbra"));
        items.add(UItem.asCheck(umbraPoppiesRow, "Тема «Маки»", "Розовые маки падают по всему экрану, интерфейс розовеет, по краям цветы. Нажимай на маки: три пойманных собираются в букет").setChecked(app.umbra.UmbraPoppies.isEnabled()));
        items.add(TextSettingsCellFactory.of(umbraPoppyOpacityRow, "Прозрачность маков", app.umbra.UmbraPoppies.opacity() + "%"));
        items.add(UItem.asCheck(umbraKeepKickedRow, "Хранить чаты, откуда кикнули", "Чат остаётся в списке с уже сохранёнными сообщениями").setChecked(app.umbra.KeepKicked.enabled()));
        items.add(UItem.asShadow(null));

    }

    @Override
    protected void onItemClick""")
edit(settings, "        var id = item.id;\n        if (id == tabletModeRow) {",
     """        var id = item.id;
        if (id == umbraPoppiesRow) {
            app.umbra.UmbraPoppies.setEnabled(!app.umbra.UmbraPoppies.isEnabled());
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(app.umbra.UmbraPoppies.isEnabled());
            }
        } else if (id == umbraPoppyOpacityRow) {
            ArrayList<String> labels = new ArrayList<>();
            final int[] values = {100, 80, 60, 40, 20};
            int selected = 0;
            for (int i = 0; i < values.length; i++) {
                labels.add(values[i] + "%");
                if (values[i] == app.umbra.UmbraPoppies.opacity()) {
                    selected = i;
                }
            }
            showPopup(labels, selected, item, view, i -> {
                app.umbra.UmbraPoppies.setOpacity(values[i]);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            });
        } else if (id == umbraKeepKickedRow) {
            app.umbra.KeepKicked.setEnabled(!app.umbra.KeepKicked.enabled());
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(app.umbra.KeepKicked.enabled());
            }
        } else if (id == tabletModeRow) {""")

print("features applied")
