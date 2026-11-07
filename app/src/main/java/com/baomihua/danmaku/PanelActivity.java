package com.baomihua.danmaku;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.baomihua.danmaku.core.CommentParser;
import com.baomihua.danmaku.core.Danmaku;
import com.baomihua.danmaku.core.DanmuApi;
import com.baomihua.danmaku.core.Models;
import com.baomihua.danmaku.core.TitleParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 主面板。对应桌面版的托盘面板：一个状态卡 + 显示设置 + 更多设置。
 */
public final class PanelActivity extends Activity {

    public static final String VERSION = "1.1.2";

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_PICK_FILE = 1003;

    private final Handler handler = new Handler();

    private Prefs prefs;
    private DanmuRepository repository;

    private TextView statusPill;
    private TextView headline;
    private TextView detail;
    private TextView mediaLine;
    private TextView sourceLine;
    private TextView positionLine;
    private TextView startButton;
    private LinearLayout permissionCard;
    private LinearLayout permissionRows;
    private Ui.Segment areaSegment;
    private Ui.Segment densitySegment;
    private String crashText = "";
    private TextView diagOverlayPerm;
    private TextView diagListenerPerm;
    private TextView diagService;
    private TextView diagWindow;
    private TextView diagScreen;
    private TextView diagSession;
    private TextView diagDanmaku;
    private TextView diagEvents;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = App.prefs(this);
        repository = new DanmuRepository(this);
        configureWindow();
        crashText = App.lastCrash(this);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        restoreServiceIfWanted();
    }

    /** 上次开启过悬浮窗的话，打开 App 就自动把它接回来，避免「怎么又没了」。 */
    private void restoreServiceIfWanted() {
        if (OverlayService.isRunning()) {
            return;
        }
        if (!prefs.isServiceWanted()) {
            return;
        }
        if (!App.canDrawOverlays(this)) {
            Diag.add("上次开过悬浮窗，但现在没有悬浮窗权限");
            return;
        }
        try {
            OverlayService.start(this);
            Diag.add("按上次状态自动恢复悬浮窗");
        } catch (Throwable e) {
            Diag.error("自动恢复悬浮窗失败", e);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Theme.BG);
        window.setNavigationBarColor(Theme.BG);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderPermissions();
        renderStatus();
        OverlayService service = OverlayService.get();
        if (service != null) {
            service.setPanelVisible(true);
        }
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
        OverlayService service = OverlayService.get();
        if (service != null) {
            service.setPanelVisible(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        repository.shutdown();
    }

    // ------------------------------------------------------------ 界面

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);
        scroll.setFillViewport(true);
        LinearLayout root = Ui.page(this);
        scroll.addView(root);

        // 标题
        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "弹幕伴侣", Theme.TEXT, 21f);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        header.addView(title);
        LinearLayout.LayoutParams titleLp = (LinearLayout.LayoutParams) title.getLayoutParams();
        titleLp.weight = 1f;
        titleLp.width = 0;
        statusPill = Ui.text(this, "未启动", Theme.DIM, 11.5f);
        statusPill.setPadding(Theme.dp(this, 10), Theme.dp(this, 4), Theme.dp(this, 10),
                Theme.dp(this, 4));
        statusPill.setBackground(Theme.pill(this, 0xFF1E242F));
        header.addView(statusPill);
        root.addView(header);
        root.addView(Ui.text(this, "把弹幕叠在你正在看的视频上 · v" + VERSION, Theme.DIM, 12f));
        root.addView(Ui.gap(this, 14));

        root.addView(buildStatusCard());
        permissionCard = buildPermissionCard();
        root.addView(permissionCard);
        root.addView(buildDiagCard());
        root.addView(buildDisplayCard());
        root.addView(buildMoreCard());
        root.addView(buildAboutCard());
        return scroll;
    }

    private LinearLayout buildStatusCard() {
        LinearLayout card = Ui.card(this);

        LinearLayout top = Ui.row(this);
        headline = Ui.text(this, "未启动", Theme.TEXT, 16.5f);
        headline.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        headline.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(headline);
        card.addView(top);

        detail = Ui.text(this, "", Theme.DIM, 12.5f);
        card.addView(detail);

        mediaLine = Ui.text(this, "", Theme.TEXT, 13f);
        mediaLine.setPadding(0, Theme.dp(this, 10), 0, 0);
        mediaLine.setMaxLines(2);
        card.addView(mediaLine);

        sourceLine = Ui.text(this, "", Theme.DIM, 12f);
        sourceLine.setMaxLines(2);
        card.addView(sourceLine);

        positionLine = Ui.text(this, "", Theme.DIM, 12f);
        card.addView(positionLine);

        LinearLayout buttons1 = Ui.row(this);
        buttons1.setPadding(0, Theme.dp(this, 12), 0, 0);
        startButton = Ui.button(this, "开启悬浮窗", true);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleService();
            }
        });
        buttons1.addView(startButton);
        buttons1.addView(Ui.gap(this, 8));
        TextView mini = Ui.button(this, "小面板", false);
        mini.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayService service = OverlayService.get();
                if (service == null) {
                    toast("先开启悬浮窗");
                    return;
                }
                // 回到播放画面，小面板会浮在上面
                moveTaskToBack(true);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        OverlayService current = OverlayService.get();
                        if (current != null) {
                            current.toggleFloatingPanel();
                        }
                    }
                }, 260);
            }
        });
        buttons1.addView(mini);
        buttons1.addView(Ui.gap(this, 8));
        TextView rematch = Ui.button(this, "重新匹配", false);
        rematch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayService service = OverlayService.get();
                if (service == null) {
                    toast("先开启悬浮窗");
                    return;
                }
                service.rematch();
                toast("正在重新匹配");
            }
        });
        buttons1.addView(rematch);
        card.addView(buttons1);

        LinearLayout buttons2 = Ui.row(this);
        buttons2.setPadding(0, Theme.dp(this, 8), 0, 0);
        TextView manual = Ui.button(this, "手动搜索 / 换源", false);
        manual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showManualSearch();
            }
        });
        buttons2.addView(manual);
        buttons2.addView(Ui.gap(this, 8));
        TextView local = Ui.button(this, "载入本地弹幕", false);
        local.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLocalFile();
            }
        });
        buttons2.addView(local);
        buttons2.addView(Ui.gap(this, 8));
        TextView calibrate = Ui.button(this, "校准", false);
        calibrate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCalibrate();
            }
        });
        buttons2.addView(calibrate);
        card.addView(buttons2);

        return card;
    }

    private LinearLayout buildPermissionCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "还差几步"));
        TextView note = Ui.text(this, "伴侣要画在被播放器占满的屏幕上，所以需要两个系统授权。", Theme.DIM, 12.5f);
        card.addView(note);
        permissionRows = new LinearLayout(this);
        permissionRows.setOrientation(LinearLayout.VERTICAL);
        permissionRows.setPadding(0, Theme.dp(this, 8), 0, 0);
        card.addView(permissionRows);
        return card;
    }

    private LinearLayout buildDiagCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "自检"));
        diagOverlayPerm = addDiagRow(card, "悬浮窗权限");
        diagListenerPerm = addDiagRow(card, "通知使用权");
        diagService = addDiagRow(card, "前台服务");
        diagWindow = addDiagRow(card, "弹幕窗");
        diagScreen = addDiagRow(card, "屏幕");
        diagSession = addDiagRow(card, "媒体会话");
        diagDanmaku = addDiagRow(card, "弹幕");
        diagEvents = Ui.text(this, "", Theme.DIM, 11f);
        diagEvents.setLineSpacing(Theme.dpF(this, 2), 1f);
        diagEvents.setPadding(0, Theme.dp(this, 8), 0, 0);
        card.addView(diagEvents);

        LinearLayout actions = Ui.row(this);
        actions.setPadding(0, Theme.dp(this, 10), 0, 0);
        TextView remount = Ui.button(this, "重新挂载", false);
        remount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayService service = OverlayService.get();
                if (service == null) {
                    toast("先开启悬浮窗");
                    return;
                }
                service.remountOverlay();
                toast("已重新挂载，回到播放器看看");
            }
        });
        actions.addView(remount);
        actions.addView(Ui.gap(this, 6));
        TextView test = Ui.button(this, "测试横幅", false);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayService service = OverlayService.get();
                if (service == null) {
                    toast("先开启悬浮窗");
                    return;
                }
                moveTaskToBack(true);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        OverlayService current = OverlayService.get();
                        if (current != null) {
                            current.showOverlayTest();
                        }
                    }
                }, 300);
                toast("回到桌面后应该能看到橙色横幅");
            }
        });
        actions.addView(test);
        actions.addView(Ui.gap(this, 6));
        TextView copy = Ui.button(this, "复制诊断", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (cb != null) {
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("danmaku-diag",
                            buildDiagText()));
                    toast("诊断信息已复制");
                }
            }
        });
        actions.addView(copy);
        card.addView(actions);
        return card;
    }

    private TextView addDiagRow(LinearLayout card, String label) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Theme.dp(this, 3), 0, Theme.dp(this, 3));
        TextView name = Ui.text(this, label, Theme.DIM, 12f);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);
        TextView value = Ui.text(this, "—", Theme.TEXT, 12f);
        value.setGravity(Gravity.END);
        row.addView(value);
        card.addView(row);
        return value;
    }

    private void renderDiag() {
        OverlayService service = OverlayService.get();
        boolean overlay = App.canDrawOverlays(this);
        boolean listener = MediaWatcher.isEnabled(this);
        diagOverlayPerm.setText(overlay ? "已授权" : "未授权");
        diagOverlayPerm.setTextColor(overlay ? Theme.GREEN : Theme.RED);
        diagListenerPerm.setText(listener ? "已授权" : "未授权");
        diagListenerPerm.setTextColor(listener ? Theme.GREEN : Theme.RED);
        diagService.setText(service == null ? "未运行" : "运行中");
        diagService.setTextColor(service == null ? Theme.DIM : Theme.GREEN);
        if (service == null) {
            diagWindow.setText("未创建");
            diagWindow.setTextColor(Theme.DIM);
            diagScreen.setText("—");
            diagSession.setText("—");
            diagDanmaku.setText("—");
        } else {
            diagWindow.setText(service.overlayInfo());
            diagWindow.setTextColor(service.isOverlayAttached() ? Theme.GREEN : Theme.RED);
            diagScreen.setText(service.screenInfo());
            diagSession.setText(service.sessionInfo());
            diagDanmaku.setText("已加载 " + service.loadedCount() + " 条 · 屏上 "
                    + service.visibleCount() + " 条");
        }
        StringBuilder events = new StringBuilder();
        String crash = crashText;
        if (crash != null && !crash.isEmpty()) {
            String[] lines = crash.split("\n");
            events.append("!! 上次崩溃: ").append(lines.length > 2 ? lines[2] : lines[0]).append('\n');
        }
        for (String line : Diag.recent(5)) {
            events.append(line).append('\n');
        }
        diagEvents.setText(events.toString().trim());
    }

    private String buildDiagText() {
        StringBuilder sb = new StringBuilder();
        sb.append("包名: ").append(getPackageName()).append('\n');
        sb.append("版本: ").append(VERSION).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") ").append(Build.MANUFACTURER)
                .append(' ').append(Build.MODEL).append('\n');
        sb.append("悬浮窗权限: ").append(App.canDrawOverlays(this)).append('\n');
        sb.append("通知使用权: ").append(MediaWatcher.isEnabled(this)).append('\n');
        OverlayService service = OverlayService.get();
        sb.append("服务: ").append(service == null ? "未运行" : "运行中").append('\n');
        if (service != null) {
            sb.append("弹幕窗: ").append(service.overlayInfo()).append('\n');
            sb.append("屏幕: ").append(service.screenInfo()).append('\n');
            sb.append("媒体会话: ").append(service.sessionInfo()).append('\n');
            sb.append("弹幕: ").append(service.loadedCount()).append(" 条 / 屏上 ")
                    .append(service.visibleCount()).append('\n');
        }
        String crash = crashText;
        if (crash != null && !crash.isEmpty()) {
            sb.append("---- 上次崩溃 ----\n").append(crash).append("\n");
        }
        sb.append("---- 最近事件 ----\n").append(Diag.dump());
        return sb.toString();
    }

    private LinearLayout buildDisplayCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "显示"));

        card.addView(Ui.switchRow(this, "显示弹幕", prefs.settings().enabled,
                "关掉后弹幕仍会跟随，只是不画出来",
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.enabled = isChecked;
                            }
                        });
                        applyToService();
                    }
                }));

        card.addView(Ui.gap(this, 6));
        card.addView(Ui.text(this, "显示区域", Theme.TEXT, 13.5f));
        String[] areaLabels = {"1/4", "1/2", "3/4", "全屏"};
        int[] areaValues = {25, 50, 75, 100};
        int areaIndex = 1;
        for (int i = 0; i < areaValues.length; i++) {
            if (areaValues[i] == prefs.settings().areaPercent) {
                areaIndex = i;
            }
        }
        final int[] areas = areaValues;
        areaSegment = Ui.segment(this, areaLabels, areaIndex, new Ui.OnSelect() {
            @Override
            public void onSelect(int index) {
                prefs.updateSetting(new Prefs.SettingEdit() {
                    @Override
                    public void apply(com.baomihua.danmaku.core.Settings s) {
                        s.areaPercent = areas[index];
                    }
                });
                applyToService();
            }
        });
        card.addView(areaSegment.root);

        card.addView(Ui.gap(this, 10));
        addSlider(card, "字号", 10, 48, 1, " px", prefs.settings().fontSp, new Ui.OnValue() {
            @Override
            public void onValue(final float value) {
                prefs.updateSetting(new Prefs.SettingEdit() {
                    @Override
                    public void apply(com.baomihua.danmaku.core.Settings s) {
                        s.fontSp = Math.round(value);
                    }
                });
                applyToService(true);
            }
        });
        addSlider(card, "速度", 0.3f, 2.2f, 0.05f, "×", prefs.settings().speed, new Ui.OnValue() {
            @Override
            public void onValue(final float value) {
                prefs.updateSetting(new Prefs.SettingEdit() {
                    @Override
                    public void apply(com.baomihua.danmaku.core.Settings s) {
                        s.speed = value;
                    }
                });
                applyToService(true);
            }
        });
        addSlider(card, "不透明度", 0.15f, 1f, 0.05f, "", prefs.settings().opacity,
                new Ui.OnValue() {
                    @Override
                    public void onValue(final float value) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.opacity = value;
                            }
                        });
                        applyToService(true);
                    }
                });
        addSlider(card, "描边", 0f, 3f, 0.2f, " dp", prefs.settings().strokeDp, new Ui.OnValue() {
            @Override
            public void onValue(final float value) {
                prefs.updateSetting(new Prefs.SettingEdit() {
                    @Override
                    public void apply(com.baomihua.danmaku.core.Settings s) {
                        s.strokeDp = value;
                    }
                });
                applyToService(true);
            }
        });
        addSlider(card, "阴影", 0f, 1f, 0.05f, "", prefs.settings().shadow, new Ui.OnValue() {
            @Override
            public void onValue(final float value) {
                prefs.updateSetting(new Prefs.SettingEdit() {
                    @Override
                    public void apply(com.baomihua.danmaku.core.Settings s) {
                        s.shadow = value;
                    }
                });
                applyToService(true);
            }
        });

        card.addView(Ui.gap(this, 8));
        card.addView(Ui.text(this, "弹幕密度", Theme.TEXT, 13.5f));
        densitySegment = Ui.segment(this, new String[]{"稀疏", "标准", "密集"},
                prefs.settings().density, new Ui.OnSelect() {
                    @Override
                    public void onSelect(int index) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.density = index;
                            }
                        });
                        applyToService(true);
                    }
                });
        card.addView(densitySegment.root);

        card.addView(Ui.switchRow(this, "底部弹幕", prefs.settings().allowBottom,
                "只在「全屏」显示区域时出现",
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.allowBottom = isChecked;
                            }
                        });
                        applyToService(true);
                    }
                }));

        card.addView(Ui.switchRow(this, "等比缩放", prefs.settings().scaleWithViewport,
                "字号随画面大小一起缩放",
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.scaleWithViewport = isChecked;
                            }
                        });
                        applyToService();
                    }
                }));
        return card;
    }

    private LinearLayout buildMoreCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "更多设置"));

        card.addView(Ui.text(this, "屏蔽关键词", Theme.TEXT, 13.5f));
        final EditText blocked = Ui.input(this, "用逗号分隔，例如：剧透，前方");
        blocked.setText(prefs.settings().blocked);
        blocked.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        blocked.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    saveBlocked(blocked);
                }
            }
        });
        card.addView(blocked);
        TextView saveBlocked = Ui.button(this, "保存屏蔽词", false);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = Theme.dp(this, 8);
        saveBlocked.setLayoutParams(sbLp);
        saveBlocked.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveBlocked(blocked);
                toast("已保存，重新加载弹幕后生效");
            }
        });
        card.addView(saveBlocked);

        card.addView(Ui.switchRow(this, "合并重复弹幕", prefs.settings().mergeDuplicates,
                "同一句话 8 秒内重复出现只显示一次",
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.mergeDuplicates = isChecked;
                            }
                        });
                        applyToService(true);
                    }
                }));

        addSlider(card, "时间偏移", -10f, 10f, 0.5f, " 秒", prefs.settings().timeOffsetMs / 1000f,
                new Ui.OnValue() {
                    @Override
                    public void onValue(final float value) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.timeOffsetMs = Math.round(value * 1000);
                            }
                        });
                        applyToService(true);
                    }
                });
        card.addView(Ui.hint(this, "正数让弹幕晚出现，负数让弹幕早出现。"));

        card.addView(Ui.gap(this, 12));
        card.addView(Ui.text(this, "弹幕服务地址", Theme.TEXT, 13.5f));
        final EditText apiInput = Ui.input(this, "https://danmu.example.com 或带 Token 的地址");
        apiInput.setText(prefs.settings().apiBase);
        apiInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.addView(apiInput);
        card.addView(Ui.hint(this, "需要弹弹play API v2 兼容的服务（例如自建的 danmu_api）。"
                + "填入服务根地址即可，程序会自己拼 /api/v2。"));

        LinearLayout apiButtons = Ui.row(this);
        apiButtons.setPadding(0, Theme.dp(this, 8), 0, 0);
        TextView save = Ui.button(this, "保存地址", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.updateSetting(new Prefs.SettingEdit() {
                    @Override
                    public void apply(com.baomihua.danmaku.core.Settings s) {
                        s.apiBase = apiInput.getText().toString().trim();
                    }
                });
                toast("地址已保存");
                renderStatus();
            }
        });
        apiButtons.addView(save);
        apiButtons.addView(Ui.gap(this, 8));
        TextView test = Ui.button(this, "测试连接", false);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection(apiInput.getText().toString().trim());
            }
        });
        apiButtons.addView(test);
        card.addView(apiButtons);

        card.addView(Ui.switchRow(this, "悬浮球", prefs.settings().bubble,
                "屏幕边缘的小圆点，点一下打开面板",
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.bubble = isChecked;
                            }
                        });
                        applyToService();
                    }
                }));

        card.addView(Ui.switchRow(this, "开机自启", prefs.settings().autoStart,
                "重启手机后自动驻留",
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.autoStart = isChecked;
                            }
                        });
                    }
                }));

        card.addView(Ui.gap(this, 10));
        TextView clear = Ui.button(this, "清空弹幕缓存与来源绑定", false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.clearCache(PanelActivity.this);
                prefs.clearBindings();
                toast("已清空");
            }
        });
        card.addView(clear);
        return card;
    }

    private LinearLayout buildAboutCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "怎么用"));
        card.addView(Ui.text(this,
                "1. 在上面的卡片里开启悬浮窗，并按提示给出悬浮窗权限和通知使用权。\n"
                        + "2. 用任意播放器看视频（本地视频、网页、其他播放器都行）。\n"
                        + "3. 伴侣会读取播放器的标题，提炼出关键片名去搜索，自动拉取弹幕；识别不准时用「手动搜索 / 换源」。\n"
                        + "4. 屏幕边上的「弹」字圆点是悬浮球：点一下弹出小面板（调字号/速度、搜片、校准都行），"
                        + "长按打开这个完整设置页，拖动可以换位置。\n"
                        + "5. 弹幕时间不对时，用小面板里的「校准」把当前画面时间告诉伴侣。",
                Theme.DIM, 12.5f));
        card.addView(Ui.hint(this, "本应用不提供影视资源，只负责把弹幕画在屏幕上。"
                + "搜索与弹幕请求会发往你自己填写的服务地址。"));
        return card;
    }

    private void addSlider(LinearLayout card, String label, float min, float max, float step,
                           String suffix, float value, Ui.OnValue callback) {
        card.addView(Ui.gap(this, 8));
        Ui.Slider slider = Ui.slider(this, label, min, max, step, suffix, callback);
        slider.setValue(value);
        card.addView(slider.root);
    }

    // ------------------------------------------------------------ 权限

    private void renderPermissions() {
        boolean overlay = App.canDrawOverlays(this);
        boolean listener = MediaWatcher.isEnabled(this);
        boolean notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        boolean missing = !overlay || !listener || !notifications;
        permissionCard.setVisibility(missing ? View.VISIBLE : View.GONE);
        permissionRows.removeAllViews();
        if (!overlay) {
            permissionRows.addView(permissionRow("悬浮窗权限", "让弹幕能画在别的 app 上面", "去授权",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestOverlayPermission();
                        }
                    }));
        }
        if (!listener) {
            permissionRows.addView(permissionRow("通知使用权", "读取播放器的标题与播放进度", "去授权",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestListenerPermission();
                        }
                    }));
        }
        if (!notifications) {
            permissionRows.addView(permissionRow("通知权限", "前台服务需要一条常驻通知", "去授权",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestNotificationPermissionIfNeeded();
                        }
                    }));
        }
    }

    private View permissionRow(String title, String note, String action, View.OnClickListener click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Theme.dp(this, 7), 0, Theme.dp(this, 7));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(Ui.text(this, title, Theme.TEXT, 13.5f));
        column.addView(Ui.text(this, note, Theme.DIM, 11.5f));
        row.addView(column);
        TextView button = Ui.button(this, action, false);
        button.setOnClickListener(click);
        row.addView(button);
        return row;
    }

    private void requestOverlayPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } catch (Exception e) {
            toast("打开授权页面失败，请到系统设置里手动开启悬浮窗权限");
        }
    }

    private void requestListenerPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            toast("打开授权页面失败，请到系统设置 → 通知使用权里开启");
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATIONS);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            renderPermissions();
            if (App.canDrawOverlays(this)) {
                toast("悬浮窗权限已开启");
            }
            return;
        }
        if (requestCode == REQ_PICK_FILE && data != null && data.getData() != null) {
            loadLocalFile(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        renderPermissions();
    }

    // ------------------------------------------------------------ 动作

    private void toggleService() {
        if (OverlayService.isRunning()) {
            OverlayService.stop(this);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    renderStatus();
                }
            }, 300);
            return;
        }
        if (!App.canDrawOverlays(this)) {
            toast("先给「显示在其他应用上层」权限");
            requestOverlayPermission();
            return;
        }
        try {
            OverlayService.start(this);
            toast("已开启，去播放器里看吧");
        } catch (Exception e) {
            toast("启动失败：" + e.getMessage());
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 服务刚起来时本页还在前台：先把弹幕和悬浮球按下去，免得盖住设置界面
                OverlayService service = OverlayService.get();
                if (service != null) {
                    service.setPanelVisible(true);
                }
                renderStatus();
            }
        }, 500);
    }

    private void applyToService() {
        applyToService(false);
    }

    private void applyToService(boolean reloadVisuals) {
        OverlayService service = OverlayService.get();
        if (service != null) {
            service.reloadSettings();
        }
        if (reloadVisuals) {
            renderStatus();
        }
    }

    private void saveBlocked(EditText input) {
        final String text = input.getText().toString();
        prefs.updateSetting(new Prefs.SettingEdit() {
            @Override
            public void apply(com.baomihua.danmaku.core.Settings s) {
                s.blocked = text;
            }
        });
    }

    private void renderStatus() {
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
        statusPill.setText(status.serviceRunning ? status.stateLabel() : "未启动");
        statusPill.setTextColor(color);
        statusPill.setBackground(Theme.pill(this, withAlpha(color, 0x22)));

        headline.setText(status.headline);
        detail.setText(status.detail);

        String media = status.mediaTitle;
        if (media == null || media.isEmpty()) {
            mediaLine.setText(status.serviceRunning ? "还没有识别到正在播放的视频" : "开启后会自动识别正在播放的视频");
            mediaLine.setTextColor(Theme.DIM);
        } else {
            mediaLine.setText(media);
            mediaLine.setTextColor(Theme.TEXT);
        }

        if (status.episodeLabel == null || status.episodeLabel.isEmpty()) {
            sourceLine.setText(status.count > 0 ? ("已加载 " + status.count + " 条弹幕") : "");
        } else {
            sourceLine.setText(status.episodeLabel + (status.count > 0 ? " · " + status.count + " 条" : ""));
        }

        StringBuilder position = new StringBuilder();
        position.append(OverlayService.formatTime(status.positionMs));
        if (status.durationMs > 0) {
            position.append(" / ").append(OverlayService.formatTime(status.durationMs));
        }
        position.append(status.playing ? "  ·  播放中" : "  ·  已暂停");
        if (status.speed != 1f && status.speed > 0) {
            position.append(String.format(java.util.Locale.US, "  ·  %.2fx", status.speed));
        }
        positionLine.setText(position.toString());

        boolean running = OverlayService.isRunning();
        startButton.setText(running ? "停止悬浮窗" : "开启悬浮窗");
        startButton.setBackground(running ? Theme.pill(this, 0xFF242B38) : Theme.pill(this, Theme.ACCENT));
        startButton.setTextColor(running ? Theme.TEXT : 0xFF16110D);
        renderDiag();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ------------------------------------------------------------ 手动搜索

    private void showManualSearch() {
        Status status = Status.get();
        String suggestion = TitleParser.parse(status.mediaTitle).searchKeyword();
        if (suggestion == null || suggestion.isEmpty()) {
            suggestion = prefs.lastTitle();
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 16);
        box.setPadding(pad, Theme.dp(this, 8), pad, 0);
        final EditText input = Ui.input(this, "输入片名，例如：葬送的芙莉莲");
        input.setText(suggestion);
        box.addView(input);
        box.addView(Ui.hint(this, "搜不到时试试原名、去掉年份或只留关键词。"));

        new AlertDialog.Builder(this)
                .setTitle("手动搜索 / 换源")
                .setView(box)
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String keyword = input.getText().toString().trim();
                        if (keyword.isEmpty()) {
                            toast("先输入片名");
                            return;
                        }
                        doSearch(keyword);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSearch(final String keyword) {
        toast("正在搜索…");
        repository.search(keyword, 0, new DanmuRepository.SearchCallback() {
            @Override
            public void onResults(List<DanmuRepository.Hit> hits) {
                if (hits == null || hits.isEmpty()) {
                    toast("没搜到「" + keyword + "」，换个写法试试");
                    return;
                }
                showResults(keyword, hits);
            }

            @Override
            public void onError(String reason) {
                toast(reason);
            }
        });
    }

    private void showResults(final String keyword, final List<DanmuRepository.Hit> hits) {
        String[] labels = new String[hits.size()];
        for (int i = 0; i < hits.size(); i++) {
            DanmuRepository.Hit hit = hits.get(i);
            labels[i] = hit.label() + String.format(java.util.Locale.US, "  （%.2f）", hit.score);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择作品与分集")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        loadHit(keyword, hits.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadHit(final String keyword, final DanmuRepository.Hit hit) {
        toast("正在载入：" + hit.label());
        repository.loadEpisode(hit.animeTitle, hit.episode, new DanmuRepository.Callback() {
            @Override
            public void onReady(String label, List<Danmaku> list, boolean fromCache) {
                OverlayService service = OverlayService.get();
                if (service == null) {
                    toast("先开启悬浮窗再载入弹幕");
                    return;
                }
                service.manualLoad(label, list);
                TitleParser.TitleInfo info = TitleParser.parse(
                        Status.get().mediaTitle.isEmpty() ? keyword : Status.get().mediaTitle);
                repository.rememberManual(info.series.isEmpty() ? keyword : info.series,
                        info.season, hit.animeTitle, null, hit.episode);
                toast("已载入 " + list.size() + " 条弹幕");
            }

            @Override
            public void onFailed(String reason) {
                toast(reason);
            }

            @Override
            public void onProgress(String note) {
                toast(note);
            }
        });
    }

    private void pickLocalFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Exception e) {
            toast("这个系统不支持文件选择器");
        }
    }

    private void loadLocalFile(Uri uri) {
        try {
            ContentResolver resolver = getContentResolver();
            InputStream in = resolver.openInputStream(uri);
            if (in == null) {
                toast("读取文件失败");
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            in.close();
            String body = new String(out.toByteArray(), "UTF-8");
            List<Danmaku> list = CommentParser.parseAuto(body);
            if (list.isEmpty()) {
                toast("没从这个文件里读到弹幕（支持 XML / 弹弹play JSON）");
                return;
            }
            OverlayService service = OverlayService.get();
            if (service == null) {
                toast("先开启悬浮窗");
                return;
            }
            String name = uri.getLastPathSegment();
            service.manualLoad(name == null ? "本地文件" : name, list);
            toast("已载入 " + list.size() + " 条弹幕");
        } catch (Exception e) {
            toast("读取失败：" + e.getMessage());
        }
    }

    private void showCalibrate() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 16);
        box.setPadding(pad, Theme.dp(this, 8), pad, 0);
        final EditText input = Ui.input(this, "例如 12:34 或 754");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(input);
        box.addView(Ui.hint(this, "把当前画面上看到的播放时间填进来，弹幕会立即对齐。"));
        new AlertDialog.Builder(this)
                .setTitle("校准时间")
                .setView(box)
                .setPositiveButton("校准", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        long ms = parseTime(input.getText().toString());
                        if (ms < 0) {
                            toast("看不懂这个时间");
                            return;
                        }
                        OverlayService service = OverlayService.get();
                        if (service == null) {
                            toast("先开启悬浮窗");
                            return;
                        }
                        service.calibrate(ms);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
            double value = Double.parseDouble(text);
            return (long) (value * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void testConnection(final String base) {
        if (base.isEmpty()) {
            toast("先填地址");
            return;
        }
        toast("正在连接…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    DanmuApi api = new DanmuApi(base, new com.baomihua.danmaku.core.UrlHttp());
                    List<Models.Anime> result = api.searchEpisodes("测试");
                    message = "连接成功，返回 " + result.size() + " 个结果";
                } catch (DanmuApi.ApiException e) {
                    message = "失败：" + e.getMessage();
                } catch (Exception e) {
                    message = "失败：" + e.getMessage();
                }
                final String finalMessage = message;
                App.runOnMain(new Runnable() {
                    @Override
                    public void run() {
                        toast(finalMessage);
                    }
                });
            }
        }, "api-test").start();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
