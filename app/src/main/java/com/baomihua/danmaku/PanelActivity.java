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
import com.baomihua.danmaku.core.OemProfile;
import com.baomihua.danmaku.core.TitleParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 主面板。对应桌面版的托盘面板：一个状态卡 + 显示设置 + 更多设置。
 */
public final class PanelActivity extends Activity {

    public static final String VERSION = "1.35";

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
    private LinearLayout oemCard;
    private LinearLayout oemRows;
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
    private TextView diagDevice;
    private TextView diagSystem;

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
        renderOem();
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
        oemCard = buildOemCard();
        root.addView(oemCard);
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
        TextView note = Ui.text(this, "伴侣要画在被播放器占满的屏幕上，还要读播放状态，所以需要几个系统授权。", Theme.DIM, 12.5f);
        card.addView(note);
        permissionRows = new LinearLayout(this);
        permissionRows.setOrientation(LinearLayout.VERTICAL);
        permissionRows.setPadding(0, Theme.dp(this, 8), 0, 0);
        card.addView(permissionRows);
        card.addView(Ui.hint(this,
                "Android 13+ 从浏览器安装的应用，如果「通知使用权」里找不到本应用、或开关是灰的："
                        + "到 应用信息 → 右上角「⋮」→ 允许受限设置，再回来授权。"));
        return card;
    }

    /** OPPO / 一加 / realme 专用的后台设置入口，其他机型不显示。 */
    private LinearLayout buildOemCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "ColorOS 后台设置"));
        card.addView(Ui.text(this,
                "ColorOS 会限制后台应用。下面几项做完，锁屏或切到别的 app 后弹幕才不容易被系统收走。",
                Theme.DIM, 12.5f));
        oemRows = new LinearLayout(this);
        oemRows.setOrientation(LinearLayout.VERTICAL);
        oemRows.setPadding(0, Theme.dp(this, 6), 0, 0);
        card.addView(oemRows);
        card.addView(Ui.hint(this,
                "手动路径参考：设置 → 应用管理 → 弹幕伴侣 → 耗电管理 → 允许后台活动；"
                        + "权限管理 → 悬浮窗 / 后台弹出界面。"));
        card.setVisibility(App.oem().isColorOs() ? View.VISIBLE : View.GONE);
        return card;
    }

    private void renderOem() {
        if (oemCard == null || oemRows == null) {
            return;
        }
        OemProfile oem = App.oem();
        oemCard.setVisibility(oem.isColorOs() ? View.VISIBLE : View.GONE);
        if (!oem.isColorOs()) {
            return;
        }
        oemRows.removeAllViews();
        final boolean batteryOk = OemSettings.isIgnoringBatteryOptimizations(this);
        oemRows.addView(oemRow("电池优化", batteryOk ? "已放行 · 系统不会限制后台" : "未放行 · 锁屏后容易被清理",
                batteryOk ? "已放行" : "去放行", batteryOk ? Theme.GREEN : Theme.RED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        OemSettings.open(PanelActivity.this,
                                OemSettings.Hop.BATTERY_OPTIMIZATION);
                    }
                }));
        oemRows.addView(oemRow("自启动", "允许弹幕伴侣在开机或后台自行启动", "去设置", Theme.DIM,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        OemSettings.open(PanelActivity.this, OemSettings.Hop.AUTO_START);
                    }
                }));
        oemRows.addView(oemRow("耗电管理", "选择「允许后台活动」，别选「智能省电」", "去设置", Theme.DIM,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        OemSettings.open(PanelActivity.this, OemSettings.Hop.POWER_USAGE);
                    }
                }));
        oemRows.addView(oemRow("后台弹出界面", "点悬浮球弹出小面板需要这个权限", "去设置", Theme.DIM,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        OemSettings.open(PanelActivity.this, OemSettings.Hop.BACKGROUND_POPUP);
                    }
                }));
    }

    private View oemRow(String title, String note, String action, int noteColor,
                        View.OnClickListener click) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Theme.dp(this, 6), 0, Theme.dp(this, 6));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(Ui.text(this, title, Theme.TEXT, 13f));
        column.addView(Ui.text(this, note, noteColor, 11.5f));
        row.addView(column);
        TextView button = Ui.button(this, action, false);
        button.setOnClickListener(click);
        row.addView(button);
        return row;
    }

    private LinearLayout buildDiagCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "自检"));
        diagDevice = addDiagRow(card, "机型");
        diagSystem = addDiagRow(card, "系统");
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
        OemProfile oem = App.oem();
        diagDevice.setText(oem.deviceLine());
        String colorOs = App.colorOsVersion();
        String system = "Android " + Build.VERSION.RELEASE;
        if (oem.isColorOs()) {
            system = "ColorOS" + (colorOs.isEmpty() ? "" : " " + colorOs) + " · " + system;
        }
        diagSystem.setText(system);
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
        OemProfile oem = App.oem();
        sb.append("机型: ").append(oem.deviceLine()).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(')');
        if (oem.isColorOs()) {
            String colorOs = App.colorOsVersion();
            sb.append(" · ColorOS").append(colorOs.isEmpty() ? "" : " " + colorOs);
        }
        sb.append('\n');
        if (oem.isColorOs()) {
            sb.append("电池优化: ")
                    .append(OemSettings.isIgnoringBatteryOptimizations(this) ? "已放行" : "未放行")
                    .append('\n');
            sb.append("后台看护: RestartJobService(15min) / 闹钟恢复\n");
        }
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

        card.addView(Ui.gap(this, 10));
        addSlider(card, "弹幕行数", 1, 30, 1, " 行", prefs.settings().maxRows,
                new Ui.OnValue() {
                    @Override
                    public void onValue(final float value) {
                        prefs.updateSetting(new Prefs.SettingEdit() {
                            @Override
                            public void apply(com.baomihua.danmaku.core.Settings s) {
                                s.maxRows = Math.round(value);
                            }
                        });
                        applyToService(true);
                    }
                });
        card.addView(Ui.hint(this,
                "从屏幕顶部往下排，行数越多占得越高；屏幕放不下时自动取最大可用行数。"));

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
                "显示在最后一行的位置",
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
        card.addView(Ui.hint(this, "需要弹弹play API v2 兼容的服务：自建 danmu_api 填根地址即可；"
                + "官方 api.dandanplay.net 还要在下面填 AppId / AppSecret。程序会自动补 /api/v2。"));

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

        card.addView(Ui.gap(this, 12));
        card.addView(Ui.text(this, "官方 API 鉴权（可选）", Theme.TEXT, 13.5f));
        card.addView(Ui.hint(this,
                "自建 danmu_api 一般不用鉴权。用 https://api.dandanplay.net 时，"
                        + "填弹弹play开发者中心分配的 AppId / AppSecret；推荐「签名」模式，Secret 不会随请求发送。"));

        final int[] authMode = {prefs.settings().apiAuthMode};
        Ui.Segment authSegment = Ui.segment(this, new String[]{"无鉴权", "签名", "凭证"},
                Math.max(0, Math.min(2, authMode[0])), new Ui.OnSelect() {
                    @Override
                    public void onSelect(int index) {
                        authMode[0] = index;
                    }
                });
        card.addView(authSegment.root);

        final EditText appIdInput = Ui.input(this, "AppId（开发者中心分配的，不是用户名）");
        appIdInput.setText(prefs.settings().apiAppId);
        card.addView(appIdInput);

        final EditText secretInput = Ui.input(this,
                prefs.apiSecret().isEmpty() ? "AppSecret" : "AppSecret 已保存，留空表示不修改");
        secretInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(secretInput);

        LinearLayout authButtons = Ui.row(this);
        authButtons.setPadding(0, Theme.dp(this, 8), 0, 0);
        TextView saveAuth = Ui.button(this, "保存鉴权", true);
        saveAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int mode = authMode[0];
                final String appId = appIdInput.getText().toString().trim();
                prefs.updateSetting(new Prefs.SettingEdit() {
                    @Override
                    public void apply(com.baomihua.danmaku.core.Settings s) {
                        s.apiAuthMode = mode;
                        s.apiAppId = appId;
                    }
                });
                String secret = secretInput.getText().toString().trim();
                boolean encrypted = true;
                if (!secret.isEmpty()) {
                    encrypted = prefs.setApiSecret(secret);
                    secretInput.setText("");
                    secretInput.setHint("AppSecret 已保存，留空表示不修改");
                }
                boolean hasSecret = !secret.isEmpty() || !prefs.apiSecret().isEmpty();
                if (mode != 0 && (appId.isEmpty() || !hasSecret)) {
                    toast("AppId 和 AppSecret 都要填，才能用官方 API");
                } else if (!encrypted) {
                    toast("已保存；本机加密不可用，Secret 以应用私有明文保存");
                } else {
                    toast("鉴权设置已保存");
                }
                renderStatus();
            }
        });
        authButtons.addView(saveAuth);
        authButtons.addView(Ui.gap(this, 8));
        TextView clearSecret = Ui.button(this, "清除密钥", false);
        clearSecret.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setApiSecret("");
                secretInput.setText("");
                secretInput.setHint("AppSecret");
                toast("AppSecret 已清除");
            }
        });
        authButtons.addView(clearSecret);
        card.addView(authButtons);

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

        card.addView(Ui.gap(this, 4));
        TextView resetBubble = Ui.button(this, "悬浮球复位到右边", false);
        resetBubble.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayService service = OverlayService.get();
                if (service != null) {
                    service.resetBubbleToRight();
                } else {
                    prefs.setBubbleRatios(1f, 0.5f);
                }
                toast("悬浮球已复位到右边");
            }
        });
        card.addView(resetBubble);

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

        card.addView(Ui.gap(this, 8));
        TextView stopService = Ui.button(this, "停止服务（移除通知）", false);
        stopService.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OverlayService.stop(PanelActivity.this);
                toast("已请求停止服务");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        renderStatus();
                    }
                }, 300);
            }
        });
        card.addView(stopService);
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
                        + "5. 想暂时收起弹幕：点主面板的「隐藏悬浮窗」，或下拉通知栏点一下通知；"
                        + "服务继续在后台，随时能再显示。\n"
                        + "6. 弹幕时间不对时，用小面板里的「校准」把当前画面时间告诉伴侣。",
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
        boolean colorOs = App.oem().isColorOs();
        boolean batteryOk = !colorOs || OemSettings.isIgnoringBatteryOptimizations(this);
        boolean missing = !overlay || !listener || !notifications || !batteryOk;
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
        if (!batteryOk) {
            permissionRows.addView(permissionRow("后台运行（ColorOS）",
                    "放行电池优化，锁屏后不容易被系统清理", "去放行",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            OemSettings.open(PanelActivity.this,
                                    OemSettings.Hop.BATTERY_OPTIMIZATION);
                            renderOem();
                        }
                    }));
        }
        renderOem();
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
            OverlayService service = OverlayService.get();
            if (service != null) {
                boolean wasHidden = service.isHidden();
                service.toggleHidden();
                toast(wasHidden ? "已恢复显示，收起面板后可见" : "已隐藏悬浮窗，点通知栏可恢复");
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    renderStatus();
                }
            }, 180);
            return;
        }
        if (!App.canDrawOverlays(this)) {
            toast("先给「显示在其他应用上层」权限");
            requestOverlayPermission();
            return;
        }
        try {
            // 从面板主动开启时强制显示；开机/闹钟恢复才沿用上次的隐藏状态
            OverlayService.start(this, true);
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
                renderPermissions();
                renderStatus();
                maybeShowColorOsGuide();
            }
        }, 500);
    }

    /**
     * ColorOS 首次开启悬浮窗时提醒两句：电池优化和自启动没放行的话，
     * 用户看到「弹幕过一会儿就没了」比看到弹窗更困惑。
     */
    private void maybeShowColorOsGuide() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        OemProfile oem = App.oem();
        if (!oem.isColorOs() || prefs.oppoGuideShown()) {
            return;
        }
        prefs.setOppoGuideShown(true);
        if (OemSettings.isIgnoringBatteryOptimizations(this)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ColorOS 还要放行两步")
                .setMessage("弹幕已经打开。ColorOS 会在锁屏或切到后台后清理应用，"
                        + "建议现在放行，否则看一会儿弹幕可能消失。\n\n"
                        + "1. 电池优化 → 允许后台活动\n"
                        + "2. 应用权限 → 自启动 / 后台弹出界面\n\n"
                        + "下面「ColorOS 后台设置」卡片里有直达按钮。")
                .setPositiveButton("去放行", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        OemSettings.open(PanelActivity.this,
                                OemSettings.Hop.BATTERY_OPTIMIZATION);
                        renderOem();
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
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
        boolean running = OverlayService.isRunning();
        OverlayService service = OverlayService.get();
        boolean hidden = running && service != null && service.isHidden();

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
        if (hidden) {
            color = Theme.BLUE;
        }
        statusPill.setText(!running ? "未启动" : hidden ? "已隐藏" : status.stateLabel());
        statusPill.setTextColor(color);
        statusPill.setBackground(Theme.pill(this, withAlpha(color, 0x22)));

        headline.setText(hidden ? "悬浮窗已隐藏" : status.headline);
        detail.setText(hidden
                ? "服务仍在后台运行。点通知栏，或点下面的「显示悬浮窗」恢复。"
                : status.detail);

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

        startButton.setText(!running ? "开启悬浮窗"
                : hidden ? "显示悬浮窗" : "隐藏悬浮窗");
        startButton.setBackground(!running
                ? Theme.pill(this, Theme.ACCENT)
                : Theme.pill(this, 0xFF242B38));
        startButton.setTextColor(!running ? 0xFF16110D : Theme.TEXT);
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
        final DanmuApi.Auth auth = prefs.apiAuth();
        if (auth.incomplete()) {
            toast("先填 AppId / AppSecret，并点「保存鉴权」");
            return;
        }
        toast("正在连接…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    DanmuApi api = new DanmuApi(base,
                            new com.baomihua.danmaku.core.UrlHttp(), auth);
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
