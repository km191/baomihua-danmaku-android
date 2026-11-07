package com.baomihua.danmaku;

import android.content.Context;
import android.content.SharedPreferences;

import com.baomihua.danmaku.core.MiniJson;
import com.baomihua.danmaku.core.Models;
import com.baomihua.danmaku.core.Settings;
import com.baomihua.danmaku.core.TitleParser;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置与「来源绑定」的持久化。
 *
 * <p>绑定记录对应桌面版的「同一部剧换集时优先沿用已选来源」：
 * 记住某个作品某一季绑定的弹幕源（animeId + 分集表），换集时直接从表里取，不需要重新搜索。
 */
public final class Prefs {

    private static final String KEY_SETTINGS = "settings_json";
    private static final String KEY_BINDINGS = "bindings_json";
    private static final String KEY_LAST_TITLE = "last_title";
    private static final String KEY_SERVICE_WANTED = "service_wanted";

    /** 一条来源绑定。 */
    public static final class Binding {
        public String series = "";
        public int season;
        public long animeId;
        public String animeTitle = "";
        public List<Models.Episode> episodes = new ArrayList<>();
        public long chosenEpisodeId;
        public String chosenEpisodeTitle = "";
        public String source = "";
        public long updatedAt;

        public Models.Episode episodeFor(int episodeNumber) {
            for (Models.Episode ep : episodes) {
                if (episodeNumberOf(ep.title) == episodeNumber) {
                    return ep;
                }
            }
            return null;
        }

        public Models.Episode chosen() {
            for (Models.Episode ep : episodes) {
                if (ep.episodeId == chosenEpisodeId) {
                    return ep;
                }
            }
            return episodes.isEmpty() ? null : episodes.get(0);
        }

        public void choose(Models.Episode ep) {
            if (ep == null) {
                return;
            }
            chosenEpisodeId = ep.episodeId;
            chosenEpisodeTitle = ep.title;
            updatedAt = System.currentTimeMillis();
        }

        private static int episodeNumberOf(String title) {
            if (title == null || title.isEmpty()) {
                return 0;
            }
            TitleParser.TitleInfo info = TitleParser.parse(title);
            return info.episode;
        }
    }

    private final SharedPreferences sp;
    private Settings cached;

    public Prefs(Context context) {
        this.sp = context.getApplicationContext().getSharedPreferences("danmu", Context.MODE_PRIVATE);
    }

    public Settings settings() {
        if (cached == null) {
            cached = Settings.fromJson(sp.getString(KEY_SETTINGS, null));
        }
        return cached;
    }

    public void save(Settings settings) {
        cached = settings;
        sp.edit().putString(KEY_SETTINGS, settings.toJson()).apply();
    }

    public void updateSetting(SettingEdit edit) {
        Settings s = settings();
        edit.apply(s);
        save(s);
    }

    public interface SettingEdit {
        void apply(Settings s);
    }

    public String lastTitle() {
        return sp.getString(KEY_LAST_TITLE, "");
    }

    /** 用户是否开启过悬浮窗：下次打开 App 自动恢复。 */
    public boolean isServiceWanted() {
        return sp.getBoolean(KEY_SERVICE_WANTED, false);
    }

    public void setServiceWanted(boolean wanted) {
        sp.edit().putBoolean(KEY_SERVICE_WANTED, wanted).apply();
    }

    public void setLastTitle(String title) {
        sp.edit().putString(KEY_LAST_TITLE, title == null ? "" : title).apply();
    }

    // ------------------------------------------------------------ 来源绑定

    public Binding findBinding(TitleParser.TitleInfo info) {
        String key = bindingKey(info);
        List<Binding> all = bindings();
        for (Binding b : all) {
            if (bindingKey(b.series, b.season).equals(key)) {
                return b;
            }
        }
        return null;
    }

    public void saveBinding(Binding binding) {
        List<Binding> all = bindings();
        String key = bindingKey(binding.series, binding.season);
        for (int i = 0; i < all.size(); i++) {
            if (bindingKey(all.get(i).series, all.get(i).season).equals(key)) {
                all.set(i, binding);
                writeBindings(all);
                return;
            }
        }
        all.add(binding);
        writeBindings(all);
    }

    public void clearBindings() {
        sp.edit().remove(KEY_BINDINGS).apply();
    }

    private static String bindingKey(TitleParser.TitleInfo info) {
        return bindingKey(info.series, info.season);
    }

    private static String bindingKey(String series, int season) {
        String s = series == null ? "" : series;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb + "#" + season;
    }

    private List<Binding> bindings() {
        List<Binding> out = new ArrayList<>();
        String json = sp.getString(KEY_BINDINGS, "");
        if (json == null || json.isEmpty()) {
            return out;
        }
        Object root = MiniJson.parseQuietly(json);
        Map<String, Object> obj = MiniJson.asObject(root);
        if (obj == null) {
            return out;
        }
        List<Object> list = MiniJson.asArray(obj.get("items"));
        if (list == null) {
            return out;
        }
        for (Object item : list) {
            Map<String, Object> m = MiniJson.asObject(item);
            if (m == null) {
                continue;
            }
            Binding b = new Binding();
            b.series = str(m, "series", "");
            b.season = (int) MiniJson.lng(m, "season", 0);
            b.animeId = MiniJson.lng(m, "animeId", 0);
            b.animeTitle = str(m, "animeTitle", "");
            b.chosenEpisodeId = MiniJson.lng(m, "chosenEpisodeId", 0);
            b.chosenEpisodeTitle = str(m, "chosenEpisodeTitle", "");
            b.source = str(m, "source", "");
            b.updatedAt = MiniJson.lng(m, "updatedAt", 0);
            List<Object> eps = MiniJson.asArray(m.get("episodes"));
            if (eps != null) {
                for (Object e : eps) {
                    Map<String, Object> em = MiniJson.asObject(e);
                    if (em == null) {
                        continue;
                    }
                    long id = MiniJson.lng(em, "id", 0);
                    if (id > 0) {
                        b.episodes.add(new Models.Episode(id, b.animeId, b.animeTitle,
                                str(em, "title", "")));
                    }
                }
            }
            out.add(b);
        }
        return out;
    }

    private void writeBindings(List<Binding> all) {
        List<Object> items = new ArrayList<>();
        for (Binding b : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("series", b.series);
            m.put("season", b.season);
            m.put("animeId", b.animeId);
            m.put("animeTitle", b.animeTitle);
            m.put("chosenEpisodeId", b.chosenEpisodeId);
            m.put("chosenEpisodeTitle", b.chosenEpisodeTitle);
            m.put("source", b.source);
            m.put("updatedAt", b.updatedAt);
            List<Object> eps = new ArrayList<>();
            for (Models.Episode ep : b.episodes) {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("id", ep.episodeId);
                em.put("title", ep.title);
                eps.add(em);
            }
            m.put("episodes", eps);
            items.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("items", items);
        sp.edit().putString(KEY_BINDINGS,
                MiniJson.write(root)).apply();
    }

    private static String str(Map<String, Object> m, String key, String fallback) {
        String v = MiniJson.str(m, key);
        return v == null ? fallback : v;
    }

    // ------------------------------------------------------------ 缓存

    public File cacheDir(Context context) {
        File dir = new File(context.getApplicationContext().getCacheDir(), "danmu");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public void clearCache(Context context) {
        File dir = cacheDir(context);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    public File cacheFile(Context context, long episodeId) {
        return new File(cacheDir(context), episodeId + ".json");
    }
}
