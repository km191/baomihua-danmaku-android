package com.baomihua.danmaku;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 用代码搭面板的几个小积木，省掉一堆 XML 布局。
 */
final class Ui {

    interface OnSelect {
        void onSelect(int index);
    }

    interface OnValue {
        void onValue(float value);
    }

    private Ui() {
    }

    static LinearLayout page(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Theme.dp(c, 14), Theme.dp(c, 14), Theme.dp(c, 14), Theme.dp(c, 28));
        return root;
    }

    static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.card(c));
        int pad = Theme.dp(c, 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theme.dp(c, 12);
        card.setLayoutParams(lp);
        return card;
    }

    static TextView sectionTitle(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(Theme.DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Theme.dp(c, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    static TextView text(Context c, String content, int color, float sp) {
        TextView tv = new TextView(c);
        tv.setText(content);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setLineSpacing(Theme.dpF(c, 3), 1f);
        return tv;
    }

    static TextView hint(Context c, String content) {
        TextView tv = text(c, content, Theme.DIM, 12);
        tv.setLineSpacing(Theme.dpF(c, 4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(c, 6);
        tv.setLayoutParams(lp);
        return tv;
    }

    static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    static View gap(Context c, float dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Theme.dp(c, dp)));
        return v;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(primary ? 0xFF16110D : Theme.TEXT);
        b.setPadding(Theme.dp(c, 14), Theme.dp(c, 9), Theme.dp(c, 14), Theme.dp(c, 9));
        b.setBackground(primary ? Theme.pill(c, Theme.ACCENT) : Theme.pill(c, 0xFF242B38));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static EditText input(Context c, String hintText) {
        EditText et = new EditText(c);
        et.setHint(hintText);
        et.setHintTextColor(0xFF5C6678);
        et.setTextColor(Theme.TEXT);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        et.setBackground(Theme.round(c, 0xFF10141B, 10));
        et.setPadding(Theme.dp(c, 12), Theme.dp(c, 8), Theme.dp(c, 12), Theme.dp(c, 8));
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(c, 6);
        et.setLayoutParams(lp);
        return et;
    }

    // ------------------------------------------------------------ 分段选择

    static final class Segment {
        final LinearLayout root;
        final List<TextView> items = new ArrayList<>();
        int selected = -1;
        OnSelect callback;

        Segment(Context c) {
            root = row(c);
            root.setBackground(Theme.round(c, 0xFF10141B, 10));
            root.setPadding(Theme.dp(c, 3), Theme.dp(c, 3), Theme.dp(c, 3), Theme.dp(c, 3));
        }

        void add(Context c, String label) {
            TextView tv = new TextView(c);
            tv.setText(label);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tv.setPadding(Theme.dp(c, 8), Theme.dp(c, 7), Theme.dp(c, 8), Theme.dp(c, 7));
            final int index = items.size();
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selected != index) {
                        select(index, true);
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            items.add(tv);
            root.addView(tv);
        }

        void select(int index, boolean notify) {
            if (index < 0 || index >= items.size()) {
                return;
            }
            selected = index;
            for (int i = 0; i < items.size(); i++) {
                TextView tv = items.get(i);
                boolean active = i == index;
                tv.setTextColor(active ? 0xFF16110D : Theme.DIM);
                tv.setBackground(active ? Theme.round(items.get(i).getContext(), Theme.ACCENT, 8)
                        : null);
            }
            if (notify && callback != null) {
                callback.onSelect(index);
            }
        }
    }

    static Segment segment(Context c, String[] labels, int selected, OnSelect callback) {
        Segment s = new Segment(c);
        for (String label : labels) {
            s.add(c, label);
        }
        s.callback = callback;
        s.select(selected, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        s.root.setLayoutParams(lp);
        return s;
    }

    // ------------------------------------------------------------ 滑块

    static final class Slider {
        final LinearLayout root;
        final SeekBar bar;
        final TextView value;
        float min;
        float max;
        float step;
        String suffix = "";
        OnValue callback;

        Slider(Context c, String label, final float min, final float max, float step,
               String suffix, OnValue callback) {
            this.min = min;
            this.max = max;
            this.step = step;
            this.suffix = suffix;
            this.callback = callback;

            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);

            LinearLayout header = row(c);
            TextView title = text(c, label, Theme.TEXT, 13.5f);
            title.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            value = text(c, "", Theme.ACCENT, 13f);
            header.addView(title);
            header.addView(value);

            bar = new SeekBar(c);
            int steps = Math.max(1, Math.round((max - min) / step));
            bar.setMax(steps);
            bar.setPadding(Theme.dp(c, 2), Theme.dp(c, 8), Theme.dp(c, 2), Theme.dp(c, 8));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Theme.dp(c, 4);
            root.setLayoutParams(lp);
            root.addView(header);
            root.addView(bar);

            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float v = valueOf(progress);
                    value.setText(format(v));
                    if (fromUser && Slider.this.callback != null) {
                        Slider.this.callback.onValue(v);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        float valueOf(int progress) {
            float v = min + progress * step;
            return Math.max(min, Math.min(max, v));
        }

        String format(float v) {
            if (step >= 1f) {
                return String.valueOf(Math.round(v)) + suffix;
            }
            return String.format(java.util.Locale.US, "%.1f", v) + suffix;
        }

        void setValue(float v) {
            int progress = Math.round((v - min) / step);
            bar.setProgress(progress);
            value.setText(format(valueOf(progress)));
        }
    }

    static Slider slider(Context c, String label, float min, float max, float step, String suffix,
                         OnValue callback) {
        return new Slider(c, label, min, max, step, suffix, callback);
    }

    /** 带说明的一行开关。 */
    static LinearLayout switchRow(Context c, String label, boolean checked, String note,
                                  final android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = row(c);
        row.setPadding(0, Theme.dp(c, 8), 0, Theme.dp(c, 8));
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(text(c, label, Theme.TEXT, 13.5f));
        if (note != null && !note.isEmpty()) {
            column.addView(text(c, note, Theme.DIM, 11.5f));
        }
        android.widget.Switch sw = new android.widget.Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(column);
        row.addView(sw);
        return row;
    }

    static LinearLayout.LayoutParams matchWidth(Context c, float topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(c, topMarginDp);
        return lp;
    }
}
