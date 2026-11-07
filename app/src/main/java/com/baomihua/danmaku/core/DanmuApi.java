package com.baomihua.danmaku.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 弹弹play API v2 客户端（兼容 danmu_api 等自建服务）。
 *
 * <p>只用到四个端点，都是公开的只读接口：
 * <pre>
 *   GET  {base}/api/v2/search/episodes?anime=关键词[&amp;episode=]
 *   GET  {base}/api/v2/bangumi/{animeId}
 *   GET  {base}/api/v2/comment/{episodeId}?withRelated=true&amp;chConvert=0
 *   POST {base}/api/v2/match
 * </pre>
 * base 可以是 {@code https://danmu.example.com}，也可以带路径 Token，
 * 例如 {@code https://danmu.example.com/TOKEN/api/v2}。
 */
public final class DanmuApi {

    public static final class ApiException extends Exception {
        public final int httpCode;

        public ApiException(int httpCode, String message) {
            super(message);
            this.httpCode = httpCode;
        }

        public ApiException(int httpCode, String message, Throwable cause) {
            super(message, cause);
            this.httpCode = httpCode;
        }
    }

    private static final int TIMEOUT_MS = 12000;
    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final String endpoint;
    private final Http http;

    public DanmuApi(String baseUrl, Http http) {
        this.endpoint = endpointOf(baseUrl);
        this.http = http;
    }

    /** 把用户填的地址规范成 {@code .../api/v2}。 */
    public static String endpointOf(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return "";
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/api/v2")) {
            // 已经是完整端点：去掉 /api/v2，后面统一拼
            base = base.substring(0, base.length() - "/api/v2".length());
        }
        return base + "/api/v2";
    }

    public String endpoint() {
        return endpoint;
    }

    public boolean isConfigured() {
        return !endpoint.isEmpty();
    }

    // ------------------------------------------------------------ 搜索

    public List<Models.Anime> searchEpisodes(String keyword) throws ApiException {
        return searchEpisodes(keyword, 0);
    }

    public List<Models.Anime> searchEpisodes(String keyword, int episode) throws ApiException {
        requireConfigured();
        String url = endpoint + "/search/episodes?anime=" + enc(keyword == null ? "" : keyword.trim());
        if (episode > 0) {
            url += "&episode=" + episode;
        }
        Map<String, Object> root = getJson(url);
        List<Models.Anime> out = new ArrayList<>();
        List<Object> animes = MiniJson.asArray(root.get("animes"));
        if (animes == null) {
            return out;
        }
        for (Object o : animes) {
            Models.Anime anime = toAnime(MiniJson.asObject(o));
            if (anime != null) {
                out.add(anime);
            }
        }
        return out;
    }

    public List<Models.Episode> episodesOf(long animeId) throws ApiException {
        requireConfigured();
        Map<String, Object> root = getJson(endpoint + "/bangumi/" + animeId);
        List<Models.Episode> out = new ArrayList<>();
        Map<String, Object> bangumi = MiniJson.asObject(root.get("bangumi"));
        if (bangumi == null) {
            return out;
        }
        String title = MiniJson.str(bangumi, "animeTitle");
        List<Object> eps = MiniJson.asArray(bangumi.get("episodes"));
        if (eps == null) {
            return out;
        }
        for (Object o : eps) {
            Models.Episode ep = toEpisode(MiniJson.asObject(o), animeId, title);
            if (ep != null) {
                out.add(ep);
            }
        }
        return out;
    }

    public List<Danmaku> comments(long episodeId) throws ApiException {
        return CommentParser.parseDandanplayJson(commentsRaw(episodeId));
    }

    /** 原始响应体，便于调用方缓存。 */
    public String commentsRaw(long episodeId) throws ApiException {
        requireConfigured();
        String url = endpoint + "/comment/" + episodeId + "?withRelated=true&chConvert=0";
        return httpSafeGet(url);
    }

    /** 文件名匹配（可选，失败不影响手动/关键词搜索）。 */
    public List<Models.Episode> match(String fileName, long videoDurationMs) throws ApiException {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", fileName == null ? "" : fileName);
        body.put("fileSize", 0);
        body.put("videoDuration", Math.max(0, videoDurationMs / 1000.0));
        body.put("matchMode", "hashAndFileName");
        String payload = MiniJson.write(body);
        Http.Response response;
        try {
            response = http.post(endpoint + "/match", payload, headers(), TIMEOUT_MS);
        } catch (Exception e) {
            throw new ApiException(0, "网络错误：" + e.getMessage(), e);
        }
        checkStatus(response);
        Map<String, Object> root = parseBody(response);
        List<Models.Episode> out = new ArrayList<>();
        if (!MiniJson.bool(root, "isMatched", false)) {
            return out;
        }
        List<Object> matches = MiniJson.asArray(root.get("matches"));
        if (matches == null) {
            return out;
        }
        for (Object o : matches) {
            Map<String, Object> m = MiniJson.asObject(o);
            if (m == null) {
                continue;
            }
            long epId = MiniJson.lng(m, "episodeId", 0);
            if (epId <= 0) {
                continue;
            }
            out.add(new Models.Episode(epId, MiniJson.lng(m, "animeId", 0),
                    MiniJson.str(m, "animeTitle"), MiniJson.str(m, "episodeTitle")));
        }
        return out;
    }

    /** 连通性自检。 */
    public void ping() throws ApiException {
        requireConfigured();
        searchEpisodes("测试");
    }

    // ------------------------------------------------------------ 内部

    private String httpSafeGet(String url) throws ApiException {
        try {
            Http.Response r = http.get(url, headers(), TIMEOUT_MS);
            checkStatus(r);
            return r.body;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(0, "网络错误：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> getJson(String url) throws ApiException {
        String body = httpSafeGet(url);
        Map<String, Object> root = MiniJson.asObject(MiniJson.parseQuietly(body));
        if (root == null) {
            throw new ApiException(0, "服务返回的不是 JSON，请确认地址指向弹幕 API");
        }
        int errorCode = (int) MiniJson.lng(root, "errorCode", 0);
        if (errorCode != 0 && !MiniJson.bool(root, "success", false)) {
            String msg = MiniJson.str(root, "errorMessage");
            throw new ApiException(errorCode, msg == null ? "服务返回错误 " + errorCode : msg);
        }
        return root;
    }

    private Map<String, Object> parseBody(Http.Response response) throws ApiException {
        Map<String, Object> root = MiniJson.asObject(MiniJson.parseQuietly(response.body));
        if (root == null) {
            throw new ApiException(0, "服务返回的不是 JSON");
        }
        return root;
    }

    private void checkStatus(Http.Response response) throws ApiException {
        if (response.code == 200) {
            return;
        }
        String hint;
        switch (response.code) {
            case 401:
            case 403:
                hint = "服务需要鉴权，暂不支持需要登录/Token 头的服务";
                break;
            case 404:
                hint = "接口不存在，请确认地址是否是弹弹play v2 兼容服务";
                break;
            case 429:
                hint = "请求过多，稍后再试";
                break;
            default:
                hint = "服务返回 HTTP " + response.code;
                break;
        }
        throw new ApiException(response.code, hint);
    }

    private void requireConfigured() throws ApiException {
        if (endpoint.isEmpty()) {
            throw new ApiException(0, "还没有填写弹幕服务地址");
        }
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("User-Agent", "DanmuCompanion-Android/1.0 (dandanplay-compatible)");
        return h;
    }

    private static Models.Anime toAnime(Map<String, Object> o) {
        if (o == null) {
            return null;
        }
        long id = MiniJson.lng(o, "animeId", 0);
        String title = MiniJson.str(o, "animeTitle");
        if (title == null) {
            title = MiniJson.str(o, "animeName");
        }
        if (title == null) {
            return null;
        }
        Models.Anime anime = new Models.Anime(id, title, MiniJson.str(o, "typeDescription"));
        List<Object> eps = MiniJson.asArray(o.get("episodes"));
        if (eps != null) {
            for (Object e : eps) {
                Models.Episode ep = toEpisode(MiniJson.asObject(e), id, title);
                if (ep != null) {
                    anime.episodes.add(ep);
                }
            }
        }
        return anime;
    }

    private static Models.Episode toEpisode(Map<String, Object> o, long animeId, String animeTitle) {
        if (o == null) {
            return null;
        }
        long id = MiniJson.lng(o, "episodeId", 0);
        if (id <= 0) {
            return null;
        }
        String title = MiniJson.str(o, "episodeTitle");
        if (title == null) {
            title = MiniJson.str(o, "title");
        }
        String anime = MiniJson.str(o, "animeTitle");
        if (anime == null) {
            anime = animeTitle;
        }
        return new Models.Episode(id, animeId, anime, title);
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    public static int maxBody() {
        return MAX_BODY;
    }
}
