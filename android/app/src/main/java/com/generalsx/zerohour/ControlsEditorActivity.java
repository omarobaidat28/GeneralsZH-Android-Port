/*
**  Command & Conquer Generals Zero Hour(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
*/

package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.InputFilter;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * GeneralsX @feature Android port 09/11/2026 The on-screen buttons editor (ControlsLayouts).
 *
 * A frame with the proportions of the phone's own screen, and in it the buttons where the game
 * will draw them: drag one to move it, tap one to change its key, label, held modifiers and size
 * or to delete it. Above the frame: add a button, the layout's opacity and overall size, and the
 * list of layouts. Every change is stored at once; there is nothing to save and nothing to lose
 * by leaving.
 *
 * The frame is a scaled copy of the screen rather than the screen itself so that the bar above it
 * never covers a place a button could go, and positions and sizes are kept as fractions of the
 * display, as the engine reads them (gxvirtualkeys::layoutRects in SDL3GameEngine.cpp).
 */
public class ControlsEditorActivity extends Activity {

    private static final int SIZE_STEP_PERCENT = 10;
    private static final int OPACITY_STEP_PERCENT = 5;
    private static final int NAME_MAX = 40;

    private ControlsLayouts.State state;
    private LayoutView layoutView;
    private MaterialButton addButton;
    private MaterialButton layoutsButton;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);
        setTitle(R.string.controls_card_title);

        state = ControlsLayouts.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        int gutter = UiKit.dim(this, R.dimen.gzh_gutter);
        root.setPadding(gutter, UiKit.dp(this, 2), gutter, UiKit.dp(this, 6));

        LinearLayout bar = UiKit.buttonRow(root);
        addButton = UiKit.button(bar, UiKit.BTN_TONAL, 0,
            getString(R.string.controls_add), this::onAddButton);
        UiKit.share(addButton, true);
        UiKit.share(UiKit.button(bar, UiKit.BTN_TONAL, 0,
            getString(R.string.controls_look), this::onLook), false);
        layoutsButton = UiKit.button(bar, UiKit.BTN_TONAL, 0, "", this::onLayouts);
        UiKit.share(layoutsButton, false);
        UiKit.share(UiKit.button(bar, UiKit.BTN_PRIMARY, 0,
            getString(android.R.string.ok), this::finish), false);

        UiKit.caption(root, getString(R.string.controls_hint));

        layoutView = new LayoutView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = UiKit.dp(this, 6);
        root.addView(layoutView, lp);

        setContentView(root);
        InsetUtil.applySafeInsets(root);
        refresh();
    }

    private void refresh() {
        addButton.setEnabled(state.active.buttons.size() < ControlsLayouts.MAX_BUTTONS);
        layoutsButton.setText(getString(R.string.controls_layouts) + ": " + state.active.name);
        layoutView.invalidate();
    }

    private void storeAndRefresh() {
        ControlsLayouts.save(this, state);
        refresh();
    }

    // --------------------------------------------------------------- buttons

    private void onAddButton() {
        ControlsLayouts.Button button = new ControlsLayouts.Button();
        button.key = firstUnusedKey();
        button.label = ControlsLayouts.defaultLabel(button.key);
        // Near the middle, where nothing of the starting layout is; each next one a step lower so
        // two added in a row do not land exactly on top of each other.
        button.x = 0.47f;
        button.y = 0.10f + (state.active.buttons.size() % 6) * 0.11f;
        showButtonDialog(button, true);
    }

    private String firstUnusedKey() {
        for (int i = 0; i < ControlsLayouts.keyCount(); i++) {
            String key = ControlsLayouts.keyAt(i);
            boolean used = false;
            for (ControlsLayouts.Button b : state.active.buttons) {
                if (b.key.equals(key)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return key;
            }
        }
        return ControlsLayouts.keyAt(0);
    }

    private void showButtonDialog(final ControlsLayouts.Button button, final boolean isNew) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        // The dialog's own context, so the fields take the dialog's colours rather than the page's.
        final Context dc = builder.getContext();
        LinearLayout body = dialogBody(dc);

        addCaption(body, getString(R.string.controls_key));
        final Spinner keys = new Spinner(dc);
        String[] names = new String[ControlsLayouts.keyCount()];
        for (int i = 0; i < names.length; i++) {
            names[i] = ControlsLayouts.defaultLabel(ControlsLayouts.keyAt(i));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(dc, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keys.setAdapter(adapter);
        final int[] shownKey = { ControlsLayouts.keyIndex(button.key) };
        keys.setSelection(shownKey[0]);
        body.addView(keys);

        final EditText label = new EditText(dc);
        label.setSingleLine(true);
        label.setHint(R.string.controls_label);
        label.setText(button.label);
        label.setFilters(new InputFilter[] { new InputFilter.LengthFilter(ControlsLayouts.LABEL_MAX) });
        body.addView(label);

        // A label nobody typed follows the key: picking W for a button still labelled with its
        // old key's name relabels it W; a label of the player's own stays.
        keys.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String previousDefault = ControlsLayouts.defaultLabel(ControlsLayouts.keyAt(shownKey[0]));
                if (label.getText().toString().trim().equals(previousDefault)) {
                    label.setText(ControlsLayouts.defaultLabel(ControlsLayouts.keyAt(position)));
                }
                shownKey[0] = position;
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                shownKey[0] = ControlsLayouts.keyIndex(button.key);
            }
        });

        // Keys held down together with the button's own. The names are the keys' own names, the
        // same in every language, as on the buttons themselves.
        LinearLayout modifiers = new LinearLayout(dc);
        modifiers.setOrientation(LinearLayout.HORIZONTAL);
        final CheckBox shift = addModifier(modifiers, "+ Shift", button.shift);
        final CheckBox ctrl = addModifier(modifiers, "+ Ctrl", button.ctrl);
        final CheckBox alt = addModifier(modifiers, "+ Alt", button.alt);
        body.addView(modifiers);

        final int sizeMin = Math.round(ControlsLayouts.SIZE_MIN * 100f);
        final int sizeMax = Math.round(ControlsLayouts.SIZE_MAX * 100f);
        final SeekBar size = addPercentBar(body, R.string.controls_size, sizeMin, sizeMax,
            SIZE_STEP_PERCENT, Math.round(button.size * 100f));

        builder.setTitle(isNew ? getString(R.string.controls_add) : button.label)
            .setView(scrolling(dc, body))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                button.key = ControlsLayouts.keyAt(Math.max(0, keys.getSelectedItemPosition()));
                button.label = ControlsLayouts.cleanLabel(label.getText().toString(), button.key);
                button.shift = shift.isChecked();
                button.ctrl = ctrl.isChecked();
                button.alt = alt.isChecked();
                button.size = percentOf(size, sizeMin, SIZE_STEP_PERCENT) / 100f;
                if (isNew) {
                    state.active.buttons.add(button);
                }
                layoutView.keepInside(button);
                storeAndRefresh();
            })
            .setNegativeButton(R.string.common_cancel, null);
        if (!isNew) {
            builder.setNeutralButton(R.string.mods_delete_button, (dialog, which) -> {
                state.active.buttons.remove(button);
                storeAndRefresh();
            });
        }
        builder.show();
    }

    // -------------------------------------------------------- opacity and size

    private void onLook() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final Context dc = builder.getContext();
        LinearLayout body = dialogBody(dc);
        final ControlsLayouts.Layout layout = state.active;

        final SeekBar opacity = addPercentBar(body, R.string.controls_opacity,
            ControlsLayouts.OPACITY_MIN, ControlsLayouts.OPACITY_MAX, OPACITY_STEP_PERCENT, layout.opacity);
        final int scaleMin = Math.round(ControlsLayouts.SCALE_MIN * 100f);
        final int scaleMax = Math.round(ControlsLayouts.SCALE_MAX * 100f);
        final SeekBar scale = addPercentBar(body, R.string.controls_size, scaleMin, scaleMax,
            SIZE_STEP_PERCENT, Math.round(layout.scale * 100f));

        final CheckBox autoHide = new CheckBox(dc);
        autoHide.setText(R.string.controls_auto_hide);
        autoHide.setChecked(layout.autoHide);
        body.addView(autoHide);

        builder.setTitle(R.string.controls_look)
            .setView(scrolling(dc, body))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                layout.opacity = percentOf(opacity, ControlsLayouts.OPACITY_MIN, OPACITY_STEP_PERCENT);
                layout.scale = percentOf(scale, scaleMin, SIZE_STEP_PERCENT) / 100f;
                layout.autoHide = autoHide.isChecked();
                // Bigger buttons may no longer fit where they stood at the edges.
                for (ControlsLayouts.Button b : layout.buttons) {
                    layoutView.keepInside(b);
                }
                storeAndRefresh();
            })
            .setNegativeButton(R.string.common_cancel, null)
            .show();
    }

    // --------------------------------------------------------------- layouts

    private void onLayouts() {
        CharSequence[] names = new CharSequence[state.layouts.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = state.layouts.get(i).name;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.controls_layouts)
            .setSingleChoiceItems(names, state.layouts.indexOf(state.active), (d, which) -> {
                d.dismiss();
                state.active = state.layouts.get(which);
                storeAndRefresh();
            })
            .setPositiveButton(R.string.controls_new_layout, (d, which) ->
                promptName(R.string.controls_new_layout, ControlsLayouts.suggestName(this, state), name -> {
                    ControlsLayouts.create(this, state, name);
                    refresh();
                }))
            .setNeutralButton(R.string.profiles_rename, (d, which) ->
                promptName(R.string.profiles_rename, state.active.name, name -> {
                    state.active.name = name;
                    storeAndRefresh();
                }))
            .setNegativeButton(R.string.mods_delete_button, (d, which) -> confirmDeleteLayout())
            .show();
        // The last layout stays: there is always one to draw.
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(state.layouts.size() > 1);
    }

    private void confirmDeleteLayout() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.controls_layouts)
            .setMessage(getString(R.string.controls_delete_confirm, state.active.name))
            .setPositiveButton(R.string.mods_delete_button, (dialog, which) -> {
                if (ControlsLayouts.deleteActive(this, state)) {
                    refresh();
                }
            })
            .setNegativeButton(R.string.common_cancel, null)
            .show();
    }

    /** One-line name dialog; an emptied field keeps {@code initial}. */
    private void promptName(int titleRes, final String initial, final Consumer<String> onName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final Context dc = builder.getContext();
        LinearLayout body = dialogBody(dc);
        final EditText input = new EditText(dc);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(NAME_MAX) });
        body.addView(input);
        builder.setTitle(titleRes)
            .setView(body)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String name = input.getText().toString().replace('\n', ' ').trim();
                onName.accept(name.isEmpty() ? initial : name);
            })
            .setNegativeButton(R.string.common_cancel, null)
            .show();
    }

    // -------------------------------------------------------- dialog pieces

    private LinearLayout dialogBody(Context dc) {
        LinearLayout body = new LinearLayout(dc);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 20);
        body.setPadding(pad, UiKit.dp(this, 8), pad, 0);
        return body;
    }

    // A landscape phone is short: the button dialog is taller than what is left under its title.
    private static ScrollView scrolling(Context dc, View body) {
        ScrollView scroll = new ScrollView(dc);
        scroll.addView(body);
        return scroll;
    }

    private static TextView addCaption(LinearLayout body, CharSequence text) {
        TextView caption = new TextView(body.getContext());
        caption.setText(text);
        body.addView(caption);
        return caption;
    }

    private static CheckBox addModifier(LinearLayout row, String name, boolean checked) {
        CheckBox box = new CheckBox(row.getContext());
        box.setText(name);
        box.setChecked(checked);
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return box;
    }

    /**
     * A caption that states the value ("Size: 120%") over a bar from {@code min} to {@code max}
     * percent in steps of {@code step}. Read the value back with {@link #percentOf}.
     */
    private SeekBar addPercentBar(LinearLayout body, final int captionRes, final int min, int max,
                                  final int step, int value) {
        final TextView caption = addCaption(body, "");
        final SeekBar bar = new SeekBar(body.getContext());
        bar.setMax((max - min) / step);
        bar.setProgress(Math.round((ControlsLayouts.clamp(value, min, max) - min) / (float) step));
        final IntConsumer show = percent -> caption.setText(getString(captionRes, percent));
        show.accept(percentOf(bar, min, step));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                show.accept(min + progress * step);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                // The caption follows every step in onProgressChanged; a touch starting changes nothing.
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                // The value is read when the dialog is confirmed (percentOf), not when the thumb is let go.
            }
        });
        body.addView(bar);
        return bar;
    }

    private static int percentOf(SeekBar bar, int min, int step) {
        return min + bar.getProgress() * step;
    }

    // ------------------------------------------------------------- the frame

    private final class LayoutView extends View {
        // The game behind the buttons is not the launcher's theme: a fixed dark ground, so white
        // translucent buttons read here as they do over the battlefield, in the day theme too.
        private static final int GROUND_COLOR = 0xFF2B3230;
        private static final int BUTTON_RGB = 0x00FFFFFF;
        private static final int HELD_RGB = 0x00FFEBA0;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF screen = new RectF();
        private final RectF rect = new RectF();
        private final float aspect;
        private final int touchSlop;
        private final float hitPadding;

        private ControlsLayouts.Button held;
        private float grabX;
        private float grabY;
        private float downX;
        private float downY;
        private boolean moved;

        LayoutView(Context context) {
            super(context);
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
            int shortSide = Math.max(1, Math.min(metrics.widthPixels, metrics.heightPixels));
            // The game is always landscape and fills the display.
            aspect = (float) longSide / shortSide;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            hitPadding = UiKit.dp(context, 6);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            float width = w;
            float height = w / aspect;
            if (height > h) {
                height = h;
                width = h * aspect;
            }
            float left = (w - width) / 2f;
            float top = (h - height) / 2f;
            screen.set(left, top, left + width, top + height);
        }

        private float side(ControlsLayouts.Button b) {
            return screen.height() * ControlsLayouts.UNIT_OF_HEIGHT * state.active.scale * b.size;
        }

        private void place(ControlsLayouts.Button b) {
            float side = side(b);
            float left = screen.left + b.x * screen.width();
            float top = screen.top + b.y * screen.height();
            rect.set(left, top, left + side, top + side);
        }

        /** Moves a button back so that all of it is on the screen. */
        void keepInside(ControlsLayouts.Button b) {
            if (screen.isEmpty()) {
                return;
            }
            float side = side(b);
            b.x = ControlsLayouts.clamp(b.x, 0f, Math.max(0f, 1f - side / screen.width()));
            b.y = ControlsLayouts.clamp(b.y, 0f, Math.max(0f, 1f - side / screen.height()));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (screen.isEmpty()) {
                return;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(GROUND_COLOR);
            canvas.drawRect(screen, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(UiKit.dp(getContext(), 1));
            paint.setColor(UiKit.color(getContext(), R.color.gzh_outline));
            canvas.drawRect(screen, paint);

            int fillAlpha = state.active.opacity * 255 / 100;
            // The engine's label size (ensureText()): a 45th of the display height times the scale.
            float textSize = screen.height() / 45f * state.active.scale * 1.3f;
            for (ControlsLayouts.Button b : state.active.buttons) {
                place(b);
                int rgb = b == held ? HELD_RGB : BUTTON_RGB;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor((fillAlpha << 24) | rgb);
                canvas.drawRect(rect, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xC8000000 | rgb);
                canvas.drawRect(rect, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(textSize);
                paint.setShadowLayer(2f, 1f, 1f, 0xFF000000);
                Paint.FontMetrics fm = paint.getFontMetrics();
                canvas.drawText(b.label, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, paint);
                paint.clearShadowLayer();
            }
        }

        // The button under a point, the one drawn last (on top) first. Small buttons get a
        // margin around them: at this scale one can be narrower than a fingertip.
        private ControlsLayouts.Button hit(float x, float y) {
            for (int i = state.active.buttons.size() - 1; i >= 0; i--) {
                ControlsLayouts.Button b = state.active.buttons.get(i);
                place(b);
                rect.inset(-hitPadding, -hitPadding);
                if (rect.contains(x, y)) {
                    return b;
                }
            }
            return null;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    held = hit(event.getX(), event.getY());
                    if (held == null) {
                        return false;
                    }
                    place(held);
                    grabX = event.getX() - rect.left;
                    grabY = event.getY() - rect.top;
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (held == null) {
                        return false;
                    }
                    if (!moved && Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                        moved = true;
                    }
                    if (moved) {
                        held.x = (event.getX() - grabX - screen.left) / screen.width();
                        held.y = (event.getY() - grabY - screen.top) / screen.height();
                        keepInside(held);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (held == null) {
                        return false;
                    }
                    ControlsLayouts.Button released = held;
                    held = null;
                    if (moved) {
                        // Where it was dragged to is kept, also when the gesture was taken away.
                        storeAndRefresh();
                    } else {
                        invalidate();
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            performClick();
                            showButtonDialog(released, false);
                        }
                    }
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            // A tap opens the tapped button's dialog from onTouchEvent(), which knows which one;
            // this keeps the click reported to accessibility services.
            return super.performClick();
        }
    }
}
