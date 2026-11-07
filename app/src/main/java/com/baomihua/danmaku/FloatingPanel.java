package com.baomihua.danmaku;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.baomihua.danmaku.core.Settings;
import com.baomihua.danmaku.core.TitleParser;

import java.util.List;

/**
 * 点悬浮球弹出的小面板：不改设置页也能调弹幕。
 *
 * <p>它是一块悬浮窗口（不是 Activity），所以播放器可以继续播、弹幕可以继续动，
 * 调字号/速度/透明度能当场看到效果；也能直接在面板里手动搜索换源、校准时间。
 */
final class FloatingPanel {

    /** 面板要问外面的事都走这个接口，方便和 Activity 版本共用逻辑。 */
    interface Host {
        Settings settings();

        void onSettingsChanged(Settings settings);

        void onSearch(String keyword, DanmuRepository.SearchCallback callback);

        void onPick(DanmuRepository.Hit hit);

        void onCalibrate(long positionMs);

        void onRematch();

        void onHide();

        void onOpenFullPanel();

        void onClose();
    }

    private final Context context;
    private final Host host;
    private final Settings settings;

    private final LinearLayout root;
    private final LinearLayout container;
    private final ScrollView scroll;
    private final TextView statusPill;
    private final TextView mediaLine;
    private final TextView sourceLine;
    private final android.widget.Switch enabledSwitch;
    private final TextView toggleLabel;

    private View settingsView;
    private View searchView;
    private View calibrateView;

    private boolean suppressListener;
    private EditText searchInput;
    private LinearLayout resultsBox;
    private EditText calibrateInput;

    FloatingPanel(Context context, Host host, Settings settings) {
        this.context = context;
        this.host = host;
        this.settings = settings;

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Theme.card(context));
        int pad = Theme.dp(context, 14);
        root.setPadding(pad, Theme.dp(context, 12), pad, pad);
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    if (searchView != null || calibrateView != null) {
                        showSettings();
                    } else {
                        FloatingPanel.this.host.onClose();
                    }
                    return true;
                }
                return false;
            }
        });
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_OUTSIDE) {
                    // 点到面板外面：关掉，但触摸继续传给下面的播放器
                    FloatingPanel.this.host.onClose();
                }
                return false;
            }
        });

        // 标题行
        LinearLayout header = Ui.row(context);
        TextView title = Ui.text(context, "弹幕伴侣", Theme.TEXT, 15f);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        statusPill = Ui.text(context, "未启动", Theme.DIM, 11f);
        statusPill.setPadding(Theme.dp(context, 8), Theme.dp(context, 3),
                Theme.dp(context, 8), Theme.dp(context, 3));
        statusPill.setBackground(Theme.pill(context, 0xFF1E242F));
        header.addView(statusPill);
        TextView close = Ui.text(context, "  ×  ", Theme.DIM, 17f);
        close.setPadding(Theme.dp(context, 6), 0, 0, 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FloatingPanel.this.host.onClose();
            }
        });
        header.addView(close);
        root.addView(header);

        mediaLine = Ui.text(context, "", Theme.TEXT, 12.5f);
        mediaLine.setMaxLines(1);
        mediaLine.setEllipsize(TextUtils.TruncateAt.END);
        mediaLine.setPadding(0, Theme.dp(context, 6), 0, 0);
        root.addView(mediaLine);

        sourceLine = Ui.text(context, "", Theme.DIM, 11f);
        sourceLine.setMaxLines(1);
        sourceLine.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(sourceLine);

        // 快捷行：开关 + 重新匹配 + 搜索 + 校准
        LinearLayout quick = Ui.row(context);
        quick.setPadding(0, Theme.dp(context, 10), 0, 0);
        enabledSwitch = new android.widget.Switch(context);
        enabledSwitch.setChecked(settings.enabled);
        enabledSwitch.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        if (suppressListener) {
                            return;
                        }
                        FloatingPanel.this.settings.enabled = isChecked;
                        FloatingPanel.this.host.onSettingsChanged(FloatingPanel.this.settings);
                    }
                });
        quick.addView(enabledSwitch);
        toggleLabel = Ui.text(context, "弹幕", Theme.DIM, 11.5f);
        quick.addView(toggleLabel);
        quick.addView(Ui.gap(context, 8));

        TextView rematch = Ui.button(context, "重新匹配", false);
        rematch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FloatingPanel.this.host.onRematch();
            }
        });
        quick.addView(rematch);
        quick.addView(Ui.gap(context, 6));
        TextView search = Ui.button(context, "搜索", false);
        search.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearch();
            }
        });
        quick.addView(search);
        quick.addView(Ui.gap(context, 6));
        TextView calibrate = Ui.button(context, "校准", false);
        calibrate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCalibrate();
            }
        });
        quick.addView(calibrate);
        root.addView(quick);

        // 内容区
        scroll = new ScrollView(context);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, Theme.dp(context, 10), 0, 0);
        scroll.addView(container, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        // 底部
        LinearLayout footer = Ui.row(context);
        footer.setPadding(0, Theme.dp(context, 8), 0, 0);
        TextView full = Ui.button(context, "完整设置", false);
        full.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FloatingPanel.this.host.onOpenFullPanel();
            }
        });
        footer.addView(full);
        footer.addView(Ui.gap(context, 6));
        TextView hide = Ui.button(context, "隐藏悬浮窗", false);
        hide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FloatingPanel.this.host.onHide();
            }
        });
        footer.addView(hide);
        root.addView(footer);

        showSettings();
        refreshStatus();
    }

    View root() {
        return root;
    }

    // ------------------------------------------------------------ 三个视图

    private void showSettings() {
        searchView = null;
        calibrateView = null;
        if (settingsView == null) {
            settingsView = buildSettingsView();
        }
        container.removeAllViews();
        container.addView(settingsView);
        scroll.scrollTo(0, 0);
    }

    private View buildSettingsView() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        addSlider(box, "弹幕行数", 1, 30, 1, " 行", settings.maxRows, new Ui.OnValue() {
            @Override
            public void onValue(float value) {
                settings.maxRows = Math.round(value);
                host.onSettingsChanged(settings);
            }
        });

        box.addView(Ui.gap(context, 6));
        addSlider(box, "字号", 10, 48, 1, " px", settings.fontSp, new Ui.OnValue() {
            @Override
            public void onValue(float value) {
                settings.fontSp = Math.round(value);
                host.onSettingsChanged(settings);
            }
        });
        addSlider(box, "速度", 0.3f, 2.2f, 0.05f, "×", settings.speed, new Ui.OnValue() {
            @Override
            public void onValue(float value) {
                settings.speed = value;
                host.onSettingsChanged(settings);
            }
        });
        addSlider(box, "不透明度", 0.15f, 1f, 0.05f, "", settings.opacity, new Ui.OnValue() {
            @Override
            public void onValue(float value) {
                settings.opacity = value;
                host.onSettingsChanged(settings);
            }
        });
        addSlider(box, "描边", 0f, 3f, 0.2f, " dp", settings.strokeDp, new Ui.OnValue() {
            @Override
            public void onValue(float value) {
                settings.strokeDp = value;
                host.onSettingsChanged(settings);
            }
        });
        addSlider(box, "阴影", 0f, 1f, 0.05f, "", settings.shadow, new Ui.OnValue() {
            @Override
            public void onValue(float value) {
                settings.shadow = value;
                host.onSettingsChanged(settings);
            }
        });
        addSlider(box, "时间偏移", -10f, 10f, 0.5f, " 秒", settings.timeOffsetMs / 1000f,
                new Ui.OnValue() {
                    @Override
                    public void onValue(float value) {
                        settings.timeOffsetMs = Math.round(value * 1000);
                        host.onSettingsChanged(settings);
                    }
                });

        box.addView(Ui.gap(context, 6));
        box.addView(Ui.text(context, "弹幕密度", Theme.TEXT, 12.5f));
        Ui.Segment density = Ui.segment(context, new String[]{"稀疏", "标准", "密集"},
                settings.density, new Ui.OnSelect() {
                    @Override
                    public void onSelect(int index) {
                        settings.density = index;
                        host.onSettingsChanged(settings);
                    }
                });
        box.addView(density.root);

        box.addView(Ui.switchRow(context, "等比缩放", settings.scaleWithViewport, null,
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        settings.scaleWithViewport = isChecked;
                        host.onSettingsChanged(settings);
                    }
                }));
        box.addView(Ui.switchRow(context, "底部弹幕", settings.allowBottom, "显示在最下面一行",
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        settings.allowBottom = isChecked;
                        host.onSettingsChanged(settings);
                    }
                }));
        return box;
    }

    private void addSlider(LinearLayout box, String label, float min, float max, float step,
                           String suffix, float value, Ui.OnValue callback) {
        Ui.Slider slider = Ui.slider(context, label, min, max, step, suffix, callback);
        slider.setValue(value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(context, 6);
        slider.root.setLayoutParams(lp);
        box.addView(slider.root);
    }

    // ------------------------------------------------------------ 搜索

    private void showSearch() {
        calibrateView = null;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = Ui.row(context);
        TextView back = Ui.button(context, "返回", false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        top.addView(back);
        box.addView(top);

        Status status = Status.get();
        String suggestion = TitleParser.parse(status.mediaTitle).searchKeyword();
        searchInput = Ui.input(context, "片名，例如：葬送的芙莉莲");
        searchInput.setText(suggestion);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        box.addView(searchInput);

        LinearLayout actions = Ui.row(context);
        actions.setPadding(0, Theme.dp(context, 8), 0, 0);
        TextView go = Ui.button(context, "搜索", true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSearch();
            }
        });
        actions.addView(go);
        actions.addView(Ui.gap(context, 6));
        TextView usePlayer = Ui.button(context, "用播放器片名", false);
        usePlayer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String title = Status.get().mediaTitle;
                if (title == null || title.isEmpty()) {
                    toast("还没识别到片名");
                    return;
                }
                searchInput.setText(TitleParser.parse(title).searchKeyword());
                startSearch();
            }
        });
        actions.addView(usePlayer);
        box.addView(actions);

        resultsBox = new LinearLayout(context);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsBox.setPadding(0, Theme.dp(context, 8), 0, 0);
        box.addView(resultsBox);

        searchView = box;
        container.removeAllViews();
        container.addView(box);
        scroll.scrollTo(0, 0);
        searchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void startSearch() {
        if (searchInput == null || resultsBox == null) {
            return;
        }
        final String keyword = searchInput.getText().toString().trim();
        if (keyword.isEmpty()) {
            toast("先输入片名");
            return;
        }
        hideKeyboard();
        resultsBox.removeAllViews();
        resultsBox.addView(Ui.text(context, "正在搜索…", Theme.DIM, 12f));
        host.onSearch(keyword, new DanmuRepository.SearchCallback() {
            @Override
            public void onResults(List<DanmuRepository.Hit> hits) {
                if (resultsBox == null) {
                    return;
                }
                resultsBox.removeAllViews();
                if (hits == null || hits.isEmpty()) {
                    resultsBox.addView(Ui.text(context, "没搜到「" + keyword + "」，换个写法试试",
                            Theme.DIM, 12f));
                    return;
                }
                resultsBox.addView(Ui.text(context, "选一个来源：", Theme.DIM, 11.5f));
                int limit = Math.min(hits.size(), 20);
                for (int i = 0; i < limit; i++) {
                    final DanmuRepository.Hit hit = hits.get(i);
                    TextView row = Ui.text(context,
                            hit.label() + String.format(java.util.Locale.US, "  %.2f", hit.score),
                            Theme.TEXT, 12.5f);
                    row.setPadding(Theme.dp(context, 8), Theme.dp(context, 9),
                            Theme.dp(context, 8), Theme.dp(context, 9));
                    row.setBackground(Theme.round(context, 0xFF10141B, 8));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = Theme.dp(context, 5);
                    row.setLayoutParams(lp);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            host.onPick(hit);
                            showSettings();
                        }
                    });
                    resultsBox.addView(row);
                }
            }

            @Override
            public void onError(String reason) {
                if (resultsBox == null) {
                    return;
                }
                resultsBox.removeAllViews();
                resultsBox.addView(Ui.text(context, reason, Theme.RED, 12f));
            }
        });
    }

    // ------------------------------------------------------------ 校准

    private void showCalibrate() {
        searchView = null;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = Ui.row(context);
        TextView back = Ui.button(context, "返回", false);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        top.addView(back);
        box.addView(top);

        calibrateInput = Ui.input(context, "例如 12:34 或 754");
        box.addView(calibrateInput);
        box.addView(Ui.text(context, "填当前画面上看到的播放时间，弹幕会立即对齐。", Theme.DIM, 11.5f));

        LinearLayout actions = Ui.row(context);
        actions.setPadding(0, Theme.dp(context, 8), 0, 0);
        TextView ok = Ui.button(context, "校准", true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long ms = parseTime(calibrateInput.getText().toString());
                if (ms < 0) {
                    toast("看不懂这个时间");
                    return;
                }
                hideKeyboard();
                host.onCalibrate(ms);
                showSettings();
            }
        });
        actions.addView(ok);
        box.addView(actions);

        calibrateView = box;
        container.removeAllViews();
        container.addView(box);
    }

    private static long parseTime(String raw) {
        if (raw == null) {
            return -1;
        }
        String text = raw.trim().replace("：", ":");
        if (text.isEmpty()) {
            return -1;
        }
        try {
            if (text.contains(":")) {
                String[] parts = text.split(":");
                if (parts.length == 2) {
                    return (Long.parseLong(parts[0].trim()) * 60
                            + Long.parseLong(parts[1].trim())) * 1000L;
                }
                if (parts.length == 3) {
                    return (Long.parseLong(parts[0].trim()) * 3600
                            + Long.parseLong(parts[1].trim()) * 60
                            + Long.parseLong(parts[2].trim())) * 1000L;
                }
                return -1;
            }
            return (long) (Double.parseDouble(text) * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ 状态

    void refreshStatus() {
        Status status = Status.get();
        int color;
        switch (status.state) {
            case Status.READY:
                color = Theme.GREEN;
                break;
            case Status.SEARCHING:
                color = Theme.BLUE;
                break;
            case Status.FAILED:
                color = Theme.RED;
                break;
            default:
                color = Theme.DIM;
                break;
        }
        statusPill.setText(status.stateLabel());
        statusPill.setTextColor(color);
        statusPill.setBackground(Theme.pill(context, (color & 0x00FFFFFF) | 0x22000000));

        String media = status.mediaTitle;
        if (media == null || media.isEmpty()) {
            mediaLine.setText("还没有识别到正在播放的视频");
            mediaLine.setTextColor(Theme.DIM);
        } else {
            mediaLine.setText(media);
            mediaLine.setTextColor(Theme.TEXT);
        }
        if (status.episodeLabel == null || status.episodeLabel.isEmpty()) {
            sourceLine.setText(status.count > 0 ? ("已加载 " + status.count + " 条") : status.headline);
        } else {
            sourceLine.setText(status.episodeLabel + (status.count > 0 ? " · " + status.count + " 条" : ""));
        }
        suppressListener = true;
        enabledSwitch.setChecked(status.serviceRunning && settings.enabled);
        suppressListener = false;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && root.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
    }

    void release() {
        hideKeyboard();
    }

    private void toast(String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }


}
