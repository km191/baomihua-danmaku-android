package com.baomihua.danmaku;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.baomihua.danmaku.core.AutoMatcher;
import com.baomihua.danmaku.core.CommentParser;
import com.baomihua.danmaku.core.Danmaku;
import com.baomihua.danmaku.core.DanmuApi;
import com.baomihua.danmaku.core.MatchScorer;
import com.baomihua.danmaku.core.Models;
import com.baomihua.danmaku.core.Settings;
import com.baomihua.danmaku.core.TitleParser;
import com.baomihua.danmaku.core.UrlHttp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把「识别到的片名」变成「一屏弹幕」：查绑定 → 搜索 → 打分 → 取弹幕 → 缓存。
 * 对应桌面版的 AutomaticMatch + Api。
 */
public final class DanmuRepository {

    public interface Callback {
        /** 匹配成功。 */
        void onReady(String label, List<Danmaku> list, boolean fromCache);

        /** 失败；reason 是可以直接展示给用户的中文说明。 */
        void onFailed(String reason);

        /** 中间进度（例如「正在搜索：xxx」）。 */
        void onProgress(String note);
    }

    public static final class Hit {
        public final Models.Episode episode;
        public final double score;
        public final String animeTitle;
        public final String reason;

        Hit(Models.Episode episode, double score, String animeTitle, String reason) {
            this.episode = episode;
            this.score = score;
            this.animeTitle = animeTitle;
            this.reason = reason;
        }

        public String label() {
            String ep = episode.title == null || episode.title.isEmpty()
                    ? ("分集 " + episode.episodeId) : episode.title;
            return animeTitle + " · " + ep;
        }
    }

    private final Context context;
    private final Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public DanmuRepository(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = App.prefs(context);
    }

    public void shutdown() {
        io.shutdownNow();
    }

    private DanmuApi api() throws DanmuApi.ApiException {
        Settings settings = prefs.settings();
        if (settings.apiBase == null || settings.apiBase.trim().isEmpty()) {
            throw new DanmuApi.ApiException(0, "还没有填写弹幕服务地址");
        }
        DanmuApi.Auth auth = prefs.apiAuth();
        if (auth.incomplete()) {
            throw new DanmuApi.ApiException(0, "已选择官方 API 鉴权，但 AppId 或 AppSecret 没填");
        }
        return new DanmuApi(settings.apiBase, new UrlHttp(), auth);
    }

    /** 按片名/季/集自动匹配。 */
    public void resolve(final String mediaTitle, final Callback callback) {
        final TitleParser.TitleInfo info = TitleParser.parse(mediaTitle);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureConfigured(callback);
                    // 1. 已有绑定：直接换集（片名认不出来时不要乱用绑定）
                    Prefs.Binding binding = info.series.isEmpty() ? null : prefs.findBinding(info);
                    if (binding != null && info.hasEpisode()) {
                        Models.Episode ep = binding.episodeFor(info.episode);
                        if (ep != null) {
                            deliver(binding.animeTitle, ep, callback, "沿用已选来源");
                            return;
                        }
                    }
                    if (binding != null && !info.hasEpisode()) {
                        Models.Episode chosen = binding.chosen();
                        if (chosen != null) {
                            deliver(binding.animeTitle, chosen, callback, "沿用已选来源");
                            return;
                        }
                    }

                    // 2. 搜索 + 打分：先用「关键标题」搜，搜不到才逐步放宽，最多三轮
                    AutoMatcher.Result found;
                    try {
                        found = AutoMatcher.find(info, new AutoMatcher.Searcher() {
                            @Override
                            public List<Models.Anime> search(String keyword, int episode)
                                    throws Exception {
                                progress(callback, "正在搜索：" + keyword);
                                return api().searchEpisodes(keyword, episode);
                            }
                        });
                    } catch (DanmuApi.ApiException e) {
                        fail(callback, e.getMessage());
                        return;
                    }
                    if (found == null) {
                        fail(callback, "用「" + info.searchKeyword() + "」没搜到，试试手动搜索");
                        return;
                    }
                    if (!found.trusted()) {
                        fail(callback, String.format(java.util.Locale.US,
                                "匹配度太低（%.2f · %s），建议手动搜索确认来源",
                                found.score, found.keyword));
                        return;
                    }
                    Models.Anime anime = found.anime;
                    Models.Episode episode = found.episode;
                    if (info.hasEpisode()) {
                        for (Models.Episode ep : anime.episodes) {
                            int no = TitleParser.parse(ep.title).episode;
                            if (no == info.episode) {
                                episode = ep;
                                break;
                            }
                        }
                    }
                    Prefs.Binding newBinding = new Prefs.Binding();
                    newBinding.series = info.series;
                    newBinding.season = info.season;
                    newBinding.animeId = anime.animeId;
                    newBinding.animeTitle = anime.title;
                    newBinding.episodes.addAll(anime.episodes);
                    newBinding.source = "自动匹配";
                    newBinding.choose(episode);
                    prefs.saveBinding(newBinding);
                    deliver(anime.title, episode, callback,
                            found.attempts > 1 ? "自动匹配 · " + found.keyword + "（第"
                                    + found.attempts + "轮）" : "自动匹配 · " + found.keyword);
                } catch (DanmuApi.ApiException e) {
                    fail(callback, e.getMessage());
                } catch (Exception e) {
                    fail(callback, "出错了：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " " + e.getMessage()));
                }
            }
        });
    }

    /** 手动搜索，返回候选列表（不自动加载）。 */
    public void search(final String keyword, final int episodeNumber, final SearchCallback callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureConfigured(null);
                    List<Models.Anime> animes = api().searchEpisodes(keyword, episodeNumber);
                    final List<Hit> hits = new ArrayList<>();
                    TitleParser.TitleInfo query = TitleParser.parse(keyword);
                    if (query.episode == 0 && episodeNumber > 0) {
                        query = TitleParser.parse(keyword + " 第" + episodeNumber + "集");
                    }
                    List<MatchScorer.Candidate> ranked = MatchScorer.rank(query, animes);
                    int limit = Math.min(ranked.size(), 30);
                    for (int i = 0; i < limit; i++) {
                        MatchScorer.Candidate c = ranked.get(i);
                        hits.add(new Hit(c.episode, c.score, c.anime.title, c.reason));
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResults(hits);
                        }
                    });
                } catch (final DanmuApi.ApiException e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(e.getMessage());
                        }
                    });
                } catch (final Exception e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError("出错了：" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    public interface SearchCallback {
        void onResults(List<Hit> hits);

        void onError(String reason);
    }

    /** 载入某个指定分集（手动搜索结果 / 换源）。 */
    public void loadEpisode(final String animeTitle, final Models.Episode episode, final Callback callback) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(animeTitle, episode, callback, "手动选择");
                } catch (DanmuApi.ApiException e) {
                    fail(callback, e.getMessage());
                } catch (Exception e) {
                    fail(callback, "出错了：" + e.getMessage());
                }
            }
        });
    }

    /** 记录用户手动选择的来源，下次同一季优先沿用。 */
    public void rememberManual(String series, int season, String animeTitle, List<Models.Episode> episodes,
                               Models.Episode chosen) {
        Prefs.Binding binding = new Prefs.Binding();
        binding.series = series;
        binding.season = season;
        binding.animeId = chosen.animeId;
        binding.animeTitle = animeTitle;
        if (episodes != null) {
            binding.episodes.addAll(episodes);
        }
        binding.source = "手动选择";
        binding.choose(chosen);
        prefs.saveBinding(binding);
    }

    private void ensureConfigured(Callback callback) throws DanmuApi.ApiException {
        Settings settings = prefs.settings();
        if (settings.apiBase == null || settings.apiBase.trim().isEmpty()) {
            throw new DanmuApi.ApiException(0, "还没有填写弹幕服务地址，先在面板「更多设置」里填");
        }
        if (prefs.apiAuth().incomplete()) {
            throw new DanmuApi.ApiException(0, "已选择官方 API 鉴权，但 AppId 或 AppSecret 没填");
        }
    }

    private void deliver(String animeTitle, Models.Episode episode, Callback callback, String via)
            throws DanmuApi.ApiException {
        String label = animeTitle == null || animeTitle.isEmpty() ? "弹幕源" : animeTitle;
        if (episode.title != null && !episode.title.isEmpty()) {
            label = label + " · " + episode.title;
        }
        File cache = prefs.cacheFile(context, episode.episodeId);
        List<Danmaku> list = readCache(cache);
        boolean fromCache = list != null && !list.isEmpty();
        if (!fromCache) {
            progress(callback, "正在获取弹幕…");
            String body = api().commentsRaw(episode.episodeId);
            list = CommentParser.parseAuto(body);
            if (list == null) {
                list = new ArrayList<>();
            }
            if (!list.isEmpty()) {
                writeCache(cache, body);
            }
        }
        final List<Danmaku> finalList = list;
        final String finalLabel = label;
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onReady(finalLabel, finalList, fromCache);
            }
        });
    }

    private List<Danmaku> readCache(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            in.close();
            return CommentParser.parseAuto(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(File file, String body) {
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.write(body.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
            // 缓存失败不影响播放
        }
    }

    private void fail(Callback callback, final String reason) {
        if (callback == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onFailed(reason);
            }
        });
    }

    private void progress(Callback callback, final String note) {
        if (callback == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onProgress(note);
            }
        });
    }
}
