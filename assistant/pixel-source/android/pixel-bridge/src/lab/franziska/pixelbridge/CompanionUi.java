package lab.franziska.pixelbridge;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Tonal native task shell. Presentation only: no service or sensor authority lives here. */
final class CompanionUi {
    // PRISM: an opaque AMOLED canvas, optical mint and a restrained cool reflection.
    static final int BACKGROUND = 0xff070c10, SURFACE = 0xff111b20, SURFACE_HIGH = 0xff1b292e;
    static final int INK = 0xfff2faf8, MUTED = 0xffb6c7ca, FAINT = 0xff94acb1;
    static final int BLUE = 0xffaef3de, ON_BLUE = 0xff082d26, OUTLINE = 0xff34494f;
    static final int WARM = 0xffd3d9ff, DANGER = 0xffffb7b1;
    private static final int DISABLED = 0xff29373c;
    private CompanionUi() {}

    static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable shape(Activity activity, int color, int radius, int border) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(activity, radius));
        if (border != Color.TRANSPARENT) result.setStroke(dp(activity, 1), border);
        return result;
    }

    private static RippleDrawable ripple(Activity activity, int color, int radius, int border) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { -android.R.attr.state_enabled }, shape(activity, DISABLED, radius, Color.TRANSPARENT));
        GradientDrawable focus = shape(activity, color, radius, Color.TRANSPARENT);
        focus.setStroke(dp(activity, 2), color == BLUE ? ON_BLUE : BLUE);
        states.addState(new int[] { android.R.attr.state_focused }, focus);
        states.addState(new int[] {}, shape(activity, color, radius, border));
        return new RippleDrawable(ColorStateList.valueOf(0x55ffffff), states,
                shape(activity, Color.WHITE, radius, Color.TRANSPARENT));
    }

    private static ColorStateList buttonText(int enabled) {
        return new ColorStateList(new int[][] { new int[] { -android.R.attr.state_enabled }, new int[] {} },
                new int[] { FAINT, enabled });
    }

    static ScrollView screen(Activity activity, LinearLayout[] holder) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BACKGROUND);
        // Android 15+ enforces edge-to-edge for this target SDK. Keep content and
        // tap targets outside live system/cutout insets, including after rotation.
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                return applySafeInsets(view, insets);
            }
        });
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        column.setPadding(pad, dp(activity, 22), pad, dp(activity, 32));
        scroll.addView(column);
        holder[0] = column;
        return scroll;
    }

    /** Insets are real window geometry, including Android 16+ enforced edge-to-edge. */
    private static WindowInsets applySafeInsets(View view, WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        }
        view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        return insets.consumeSystemWindowInsets();
    }

    /** Stable task navigation. Legacy settings destinations remain reachable, with ordinary Back. */
    static View withNavigation(final Activity activity, final ScrollView scroll, String selected) {
        final LinearLayout frame = new LinearLayout(activity);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundColor(BACKGROUND);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        final boolean largeText = activity.getResources().getConfiguration().fontScale >= 1.3f;
        final LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(largeText ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6));
        bar.setBackground(shape(activity, SURFACE, 30, OUTLINE));
        // Intrinsic height for wrapped/scaled labels; navigation never overlays scroll content.
        final LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.leftMargin = dp(activity, 12);
        barLp.rightMargin = dp(activity, 12);
        barLp.bottomMargin = dp(activity, 10);
        frame.addView(bar, barLp);
        frame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                // Editing gets the available viewport; the four tasks return when IME closes.
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    bar.setVisibility(insets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE);
                return applySafeInsets(view, insets);
            }
        });
        if (largeText) {
            LinearLayout first = navigationRow(activity, bar);
            tab(activity, first, "talk", "Разговор", CompanionDestination.TALK, BridgeActivity.class, true);
            tab(activity, first, "messages", "Сообщения", CompanionDestination.MESSAGES, PixelCapabilitiesActivity.class, true);
            LinearLayout second = navigationRow(activity, bar);
            tab(activity, second, "route", "Маршрут", CompanionDestination.ROUTE, BridgeActivity.class, true);
            tab(activity, second, "media", "Медиа", CompanionDestination.MEDIA, PhotoSyncActivity.class, true);
        } else {
            tab(activity, bar, "talk", "Разговор", CompanionDestination.TALK, BridgeActivity.class, false);
            tab(activity, bar, "messages", "Сообщения", CompanionDestination.MESSAGES, PixelCapabilitiesActivity.class, false);
            tab(activity, bar, "route", "Маршрут", CompanionDestination.ROUTE, BridgeActivity.class, false);
            tab(activity, bar, "media", "Медиа", CompanionDestination.MEDIA, PhotoSyncActivity.class, false);
        }
        selectNavigation(frame, CompanionDestination.task(selected));
        return frame;
    }

    private static LinearLayout navigationRow(Activity activity, LinearLayout bar) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private static void tab(final Activity activity, LinearLayout bar, String icon, final String title,
                            final String section, final Class<? extends Activity> destination,
                            boolean largeText) {
        LinearLayout item = new LinearLayout(activity);
        item.setTag("task:" + section);
        item.setGravity(Gravity.CENTER);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setMinimumHeight(dp(activity, 64));
        item.setPadding(dp(activity, 4), dp(activity, 7), dp(activity, 4), dp(activity, 7));
        item.setClickable(true);
        item.setFocusable(true);
        item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                // Same-Activity sections switch locally, including returning from secondary tools.
                // External/reordered instances receive the explicit section extra.
                if (activity instanceof BridgeActivity && destination == BridgeActivity.class) {
                    ((BridgeActivity) activity).openSection(section); return;
                }
                if (activity instanceof PixelCapabilitiesActivity && destination == PixelCapabilitiesActivity.class) {
                    ((PixelCapabilitiesActivity) activity).openSection(section); return;
                }
                if (view.isSelected()) return;
                android.content.Intent intent = new android.content.Intent(activity, destination);
                intent.putExtra(CompanionDestination.EXTRA_SECTION, section);
                intent.addFlags((destination == BridgeActivity.class
                        ? android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        : android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(intent);
            }
        });
        View glyph = new NavigationIcon(activity, icon, MUTED);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(glyph, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)));
        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextSize(13);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(label, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(activity, 3);
        lp.rightMargin = dp(activity, 3);
        bar.addView(item, lp);
    }

    static void selectNavigation(View view, String selected) {
        if (view instanceof LinearLayout) {
            LinearLayout group = (LinearLayout) view;
            Object tag = group.getTag();
            if (tag instanceof String && ((String) tag).startsWith("task:")) {
                boolean active = tag.equals("task:" + selected);
                group.setSelected(active);
                Activity activity = (Activity) group.getContext();
                group.setBackground(ripple(activity, active ? 0xff25463e : Color.TRANSPARENT, 22, Color.TRANSPARENT));
                TextView label = (TextView) group.getChildAt(1);
                label.setTextColor(active ? BLUE : MUTED);
                ((NavigationIcon) group.getChildAt(0)).setTone(active ? BLUE : MUTED);
                group.setContentDescription(label.getText() + (active ? ", текущий раздел" : ", открыть раздел"));
                return;
            }
            for (int i = 0; i < group.getChildCount(); ++i) selectNavigation(group.getChildAt(i), selected);
        }
    }

    static void brand(Activity activity, LinearLayout parent) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.bottomMargin = dp(activity, 24);
        parent.addView(row, rowLp);
        View mark = new NavigationIcon(activity, "prism", BLUE);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mark.setBackground(shape(activity, SURFACE, 18, OUTLINE));
        row.addView(mark, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(activity, 15), 0, 0, 0);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView name = text(activity, words, "Франциска", 23, INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setPadding(0, 0, 0, 0);
        TextView suffix = text(activity, words, "GLASS / PIXEL", 13, FAINT);
        suffix.setLetterSpacing(0.04f);
        suffix.setPadding(0, 0, 0, 0);
    }

    static LinearLayout card(Activity activity, LinearLayout parent) {
        return panel(activity, parent, SURFACE, OUTLINE);
    }

    static LinearLayout hero(Activity activity, LinearLayout parent) {
        LinearLayout card = panel(activity, parent, SURFACE, OUTLINE);
        GradientDrawable optical = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xff163632, 0xff15272e, 0xff162332 });
        optical.setCornerRadius(dp(activity, 32));
        optical.setStroke(dp(activity, 1), 0xff3b5d5e);
        card.setBackground(optical);
        return card;
    }

    /** Static optical illustration; never represents recording or a live connection. */
    static void prismHeadline(Activity activity, LinearLayout parent, String title) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        boolean large = activity.getResources().getConfiguration().fontScale >= 1.3f;
        TextView headline = text(activity, row, title, large ? 32 : 38, INK);
        headline.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        headline.setLetterSpacing(-0.025f);
        headline.setLineSpacing(0, 1f);
        if (android.os.Build.VERSION.SDK_INT >= 28) headline.setAccessibilityHeading(true);
        headline.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        if (!large) {
            View prism = new PrismIllustration(activity);
            prism.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(prism, new LinearLayout.LayoutParams(dp(activity, 88), dp(activity, 86)));
        }
    }

    /** Local progressive disclosure only. Essential action consequences stay outside it. */
    static View disclosure(final Activity activity, LinearLayout parent, final String title, String detail) {
        LinearLayout group = new LinearLayout(activity); group.setOrientation(LinearLayout.VERTICAL);
        parent.addView(group, new LinearLayout.LayoutParams(-1, -2));
        final Button toggle = button(activity, group, title + "  +", null);
        quiet(toggle, activity); toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        final TextView body = text(activity, group, detail, 14, MUTED); body.setVisibility(View.GONE);
        toggle.setContentDescription(title + ", развернуть пояснение");
        if (android.os.Build.VERSION.SDK_INT >= 30) toggle.setStateDescription("Свёрнуто");
        toggle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(title + (open ? "  −" : "  +"));
            if (android.os.Build.VERSION.SDK_INT >= 30) toggle.setStateDescription(open ? "Развёрнуто" : "Свёрнуто");
            toggle.setContentDescription(title + (open ? ", свернуть пояснение" : ", развернуть пояснение"));
        }});
        return group;
    }

    static TextView badge(Activity activity, LinearLayout parent, String title) {
        TextView badge = text(activity, parent, title, 13, BLUE);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        badge.setLetterSpacing(0.04f);
        badge.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        badge.setBackground(shape(activity, 0xff203c38, 10, Color.TRANSPARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.bottomMargin = dp(activity, 14); badge.setLayoutParams(lp);
        return badge;
    }

    private static LinearLayout panel(Activity activity, LinearLayout parent, int color, int border) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(shape(activity, color, 28, border));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(activity, 14);
        parent.addView(card, lp);
        return card;
    }

    static TextView text(Activity activity, LinearLayout parent, String value, int sp, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setLineSpacing(dp(activity, 2), 1f);
        text.setPadding(0, 0, 0, dp(activity, 8));
        parent.addView(text);
        return text;
    }

    static TextView eyebrow(Activity activity, LinearLayout parent, String value) {
        TextView label = text(activity, parent, value, 13, WARM);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(0.04f);
        return label;
    }

    static TextView heading(Activity activity, LinearLayout parent, String title) {
        TextView label = text(activity, parent, title, 32, INK);
        label.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        label.setLetterSpacing(-0.015f);
        label.setPadding(0, 0, 0, dp(activity, 18));
        if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        return label;
    }

    static TextView section(Activity activity, LinearLayout parent, String title) {
        TextView label = text(activity, parent, title, 21, INK);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(0, dp(activity, 11), 0, dp(activity, 12));
        if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        return label;
    }

    static Button button(Activity activity, LinearLayout parent, String title, View.OnClickListener action) {
        Button button = new Button(activity);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(buttonText(ON_BLUE));
        button.setBackground(ripple(activity, BLUE, 20, Color.TRANSPARENT));
        button.setMinHeight(dp(activity, 56));
        button.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10));
        button.setElevation(0f);
        button.setOnClickListener(action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(activity, 8);
        parent.addView(button, lp);
        return button;
    }

    static void secondary(Button button, Activity activity) {
        button.setTextColor(buttonText(BLUE));
        button.setBackground(ripple(activity, SURFACE_HIGH, 20, OUTLINE));
    }

    static void quiet(Button button, Activity activity) {
        button.setTextColor(buttonText(BLUE));
        button.setBackground(ripple(activity, Color.TRANSPARENT, 20, Color.TRANSPARENT));
    }

    static void stop(Button button, Activity activity) {
        button.setTextColor(buttonText(DANGER));
        button.setBackground(ripple(activity, 0xff332528, 20, 0xff735152));
    }

    static void input(Activity activity, EditText field) {
        field.setTextColor(INK);
        field.setHintTextColor(FAINT);
        field.setTextSize(16);
        field.setMinHeight(dp(activity, 56));
        field.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
        field.setBackground(ripple(activity, SURFACE_HIGH, 16, OUTLINE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(activity, 8);
        field.setLayoutParams(lp);
    }

    /** A persistent native field label; hints can disappear without losing its name. */
    static void fieldLabel(Activity activity, LinearLayout parent, EditText field, String title) {
        if (field.getId() == View.NO_ID) {
            // These fields previously had no hierarchy-save ID. A label must not
            // introduce implicit draft or password persistence; owner-managed Bundle code stays in charge.
            field.setSaveEnabled(false);
            field.setId(View.generateViewId());
        }
        TextView label = text(activity, parent, title, 14, MUTED);
        label.setLabelFor(field.getId());
        label.setPadding(0, dp(activity, 12), 0, 0);
    }

    /** A single accessible tap target; navigation never implies that a service has started. */
    static View navigationRow(Activity activity, LinearLayout parent, String iconName,
                              String title, String detail, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 14), dp(activity, 14));
        row.setBackground(ripple(activity, SURFACE, 22, OUTLINE));
        row.setMinimumHeight(dp(activity, 78));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(action);
        row.setContentDescription(title + ". " + detail);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });

        View icon = new NavigationIcon(activity, iconName, BLUE);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setBackground(shape(activity, SURFACE_HIGH, 15, Color.TRANSPARENT));
        row.addView(icon, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(activity, 14), 0, dp(activity, 6), 0);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(labels, labelsParams);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name = text(activity, labels, title, 16, INK);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setPadding(0, 0, 0, dp(activity, 2));
        TextView summary = text(activity, labels, detail, 14, MUTED);
        summary.setPadding(0, 0, 0, 0);
        TextView arrow = new TextView(activity);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        arrow.setText("›");
        arrow.setTextSize(28);
        arrow.setTextColor(FAINT);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 40)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(activity, 9);
        parent.addView(row, lp);
        return row;
    }

    private static final class PrismIllustration extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path face = new Path();
        private final LinearGradient light = new LinearGradient(12, 8, 78, 82,
                new int[] { 0x66e0fff7, 0x249eebdb, 0x4491a6fa }, null, Shader.TileMode.CLAMP);
        PrismIllustration(Activity activity) { super(activity); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); canvas.scale(getWidth()/88f, getHeight()/86f);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1); p.setColor(0xff54797c);
            canvas.drawLine(6, 66, 80, 66, p); canvas.drawLine(20, 76, 71, 76, p);
            face.reset(); face.moveTo(18, 17); face.lineTo(63, 9); face.lineTo(76, 55);
            face.lineTo(30, 66); face.close();
            p.setStyle(Paint.Style.FILL); p.setShader(light); canvas.drawPath(face,p); p.setShader(null);
            p.setStyle(Paint.Style.STROKE); p.setColor(0xffb3eedf); p.setStrokeWidth(1.3f); canvas.drawPath(face,p);
            p.setColor(0xffdae1ff); canvas.drawLine(31, 29, 69, 22, p); canvas.drawLine(31, 29, 43, 62,p);
            p.setColor(0xff89bfc2); canvas.drawLine(18,17,31,29,p); canvas.drawLine(31,29,30,66,p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xffddfff7); canvas.drawCircle(63,9,2.2f,p);
            canvas.restore();
        }
    }

    private static final class NavigationIcon extends View {
        private final String kind;
        private int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        NavigationIcon(Activity activity, String kind, int color) { super(activity); this.kind = kind; this.color = color; }
        void setTone(int tone) { color = tone; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 48f, getHeight() / 48f);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.1f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if ("prism".equals(kind)) {
                canvas.drawRoundRect(new RectF(12, 12, 35, 35), 5, 5, paint);
                canvas.drawLine(19, 12, 19, 35, paint);
                canvas.drawLine(19, 28, 35, 20, paint);
            } else if ("talk".equals(kind) || "messages".equals(kind)) {
                canvas.drawRoundRect(new RectF(11, 12, 37, 31), 6, 6, paint);
                canvas.drawLine(17, 31, 17, 37, paint); canvas.drawLine(17, 37, 25, 31, paint);
                if ("talk".equals(kind)) {
                    canvas.drawLine(19, 21, 19, 25, paint); canvas.drawLine(24, 18, 24, 27, paint);
                    canvas.drawLine(29, 21, 29, 25, paint);
                } else {
                    canvas.drawLine(18, 19, 30, 19, paint); canvas.drawLine(18, 24, 27, 24, paint);
                }
            } else if ("route".equals(kind)) {
                canvas.drawCircle(15, 34, 3, paint); canvas.drawCircle(33, 14, 3, paint);
                canvas.drawLine(15, 31, 15, 21, paint); canvas.drawLine(15, 21, 33, 21, paint);
                canvas.drawLine(33, 21, 33, 17, paint);
            } else if ("home".equals(kind)) {
                canvas.drawLine(12, 23, 24, 13, paint); canvas.drawLine(24, 13, 36, 23, paint);
                canvas.drawLine(16, 20, 16, 35, paint); canvas.drawLine(32, 20, 32, 35, paint);
                canvas.drawLine(16, 35, 32, 35, paint);
            } else if ("media".equals(kind)) {
                canvas.drawRoundRect(new RectF(13, 15, 35, 33), 3, 3, paint);
                canvas.drawCircle(19, 21, 2, paint);
                canvas.drawLine(15, 30, 22, 24, paint); canvas.drawLine(22, 24, 27, 28, paint);
                canvas.drawLine(27, 28, 32, 23, paint);
            } else if ("cards".equals(kind)) {
                canvas.drawRoundRect(new RectF(15, 12, 34, 32), 3, 3, paint);
                canvas.drawLine(18, 36, 31, 36, paint);
                canvas.drawLine(19, 18, 30, 18, paint); canvas.drawLine(19, 23, 28, 23, paint);
            } else if ("glass".equals(kind)) {
                canvas.drawRoundRect(new RectF(10, 19, 22, 30), 4, 4, paint);
                canvas.drawRoundRect(new RectF(26, 19, 38, 30), 4, 4, paint);
                canvas.drawLine(22, 22, 26, 22, paint);
            } else if ("link".equals(kind)) {
                canvas.drawArc(new RectF(11, 15, 29, 33), 45, 275, false, paint);
                canvas.drawArc(new RectF(19, 15, 37, 33), 225, 275, false, paint);
            } else {
                canvas.drawCircle(15, 24, 1.5f, paint);
                canvas.drawCircle(24, 24, 1.5f, paint);
                canvas.drawCircle(33, 24, 1.5f, paint);
            }
            canvas.restore();
        }
    }
}
