package com.baomihua.danmaku.core;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 弹弹play API v2 客户端。
 *
 * <p>兼容两类服务：
 * <ul>
 *   <li><b>自建 danmu_api</b>：无鉴权，直接填根地址；</li>
 *   <li><b>官方 api.dandanplay.net</b>：需要开发者中心分配的 AppId / AppSecret，
 *       支持官方两种鉴权方式——签名（推荐，Secret 不上行）与凭证。</li>
 * </ul>
 *
 * <p>只用到四个公开只读端点：
 * <pre>
 *   GET  {base}/api/v2/search/episodes?anime=关键词[&amp;episode=]
 *   GET  {base}/api/v2/bangumi/{animeId}
 *   GET  {base}/api/v2/comment/{episodeId}?withRelated=true&amp;chConvert=0
 *   POST {base}/api/v2/match
 * </pre>
 *
 * <p>base 可以是 {@code https://danmu.example.com}、带路径 Token 的地址，
 * 或带查询参数 Token 的地址（{@code https://danmu.example.com?token=xxx}）。
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

    /** 官方 API v2 鉴权参数。 */
    public static final class Auth {
        /** 不鉴权（自建 danmu_api）。 */
        public static final int MODE_NONE = 0;
        /** 签名模式：发 X-AppId / X-Timestamp / X-Signature。 */
        public static final int MODE_SIGNATURE = 1;
        /** 凭证模式：发 X-AppId / X-AppSecret（仅建议在服务端使用）。 */
        public static final int MODE_CREDENTIAL = 2;

        public final int mode;
        public final String appId;
        public final String appSecret;

        public Auth(int mode, String appId, String appSecret) {
            this.mode = mode < MODE_NONE || mode > MODE_CREDENTIAL ? MODE_NONE : mode;
            this.appId = trim(appId);
            this.appSecret = trim(appSecret);
        }

        public static Auth none() {
            return new Auth(MODE_NONE, "", "");
        }

        /** 鉴权参数齐全，可以真正发请求头。 */
        public boolean active() {
            return mode != MODE_NONE && !appId.isEmpty() && !appSecret.isEmpty();
        }

        /** 选了鉴权但没填全，需要在界面上提示。 */
        public boolean incomplete() {
            return mode != MODE_NONE && (appId.isEmpty() || appSecret.isEmpty());
        }
    }

    private static final int TIMEOUT_MS = 12000;
    private static final int MAX_BODY = 16 * 1024 * 1024;

    private final String endpoint;
    private final Http http;
    private final Auth auth;

    public DanmuApi(String baseUrl, Http http) {
        this(baseUrl, http, Auth.none());
    }

    public DanmuApi(String baseUrl, Http http, Auth auth) {
        this.endpoint = endpointOf(baseUrl);
        this.http = http;
        this.auth = auth == null ? Auth.none() : auth;
    }

    /**
     * 把用户填的地址规范成 {@code .../api/v2}。
     *
     * <p>保留查询参数（例如 {@code ?token=xxx}），去掉重复的 {@code /api/v2}。
     */
    public static String endpointOf(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            return "";
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        int fragment = base.indexOf('#');
        if (fragment >= 0) {
            base = base.substring(0, fragment);
        }
        String query = "";
        int queryAt = base.indexOf('?');
        if (queryAt >= 0) {
            query = base.substring(queryAt);
            base = base.substring(0, queryAt);
            int fragmentAt = query.indexOf('#');
            if (fragmentAt >= 0) {
                query = query.substring(0, fragmentAt);
            }
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/api/v2")) {
            base = base.substring(0, base.length() - "/api/v2".length());
        }
        return base + "/api/v2" + query;
    }

    public String endpoint() {
        return endpoint;
    }

    public boolean isConfigured() {
        return !endpoint.isEmpty();
    }

    public boolean authIncomplete() {
        return auth.incomplete();
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
        List<Object> animes = MiniJson.asArray(root.get("animes"));
        if (animes == null) {
            // 部分代理会包一层 {"success":true,"data":{"animes":[...]}}
            Map<String, Object> data = MiniJson.asObject(root.get("data"));
            if (data != null) {
                animes = MiniJson.asArray(data.get("animes"));
            }
        }
        List<Models.Anime> out = new ArrayList<>();
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
        Map<String, Object> bangumi = MiniJson.asObject(root.get("bangumi"));
        if (bangumi == null) {
            Map<String, Object> data = MiniJson.asObject(root.get("data"));
            if (data != null) {
                bangumi = MiniJson.asObject(data.get("bangumi"));
            }
        }
        List<Models.Episode> out = new ArrayList<>();
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
        String body = httpSafeGet(url);
        checkBusinessError(body);
        return body;
    }

    /** 文件名匹配（可选，失败不影响手动/关键词搜索）。 */
    public List<Models.Episode> match(String fileName, long videoDurationMs) throws ApiException {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", fileName == null ? "" : fileName);
        long durationSeconds = Math.max(0L, videoDurationMs) / 1000L;
        if (durationSeconds > 0) {
            body.put("videoDuration", durationSeconds);
        }
        // 播放器在别的 app 里，拿不到视频文件，只能按文件名匹配。
        body.put("matchMode", "fileNameOnly");
        String payload = MiniJson.write(body);
        Http.Response response;
        try {
            response = http.post(endpoint + "/match", payload, headers(pathOf(endpoint + "/match")),
                    TIMEOUT_MS);
        } catch (Exception e) {
            throw new ApiException(0, "网络错误：" + e.getMessage(), e);
        }
        checkStatus(response);
        Map<String, Object> root = parseBody(response);
        checkBusinessError(root);
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

    // ------------------------------------------------------------ 鉴权

    /**
     * 官方签名：{@code base64(sha256(AppId + Timestamp + Path + AppSecret))}。
     * Path 是不含查询参数的路径，例如 {@code /api/v2/comment/123450001}。
     */
    public static String signature(String appId, long timestamp, String path, String appSecret) {
        String data = (appId == null ? "" : appId) + timestamp
                + (path == null ? "" : path) + (appSecret == null ? "" : appSecret);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64Codec.encode(digest.digest(data.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> headers(String path) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "DanmuCompanion-Android/1.3 (dandanplay-v2)");
        if (!auth.active()) {
            return headers;
        }
        headers.put("X-AppId", auth.appId);
        if (auth.mode == Auth.MODE_CREDENTIAL) {
            headers.put("X-AppSecret", auth.appSecret);
        } else {
            long timestamp = System.currentTimeMillis() / 1000L;
            headers.put("X-Timestamp", String.valueOf(timestamp));
            headers.put("X-Signature", signature(auth.appId, timestamp, path, auth.appSecret));
        }
        return headers;
    }

    private static String pathOf(String url) {
        try {
            String path = new URL(url).getPath();
            return path == null ? "" : path;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ 内部

    private String httpSafeGet(String url) throws ApiException {
        try {
            Http.Response r = http.get(url, headers(pathOf(url)), TIMEOUT_MS);
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
        checkBusinessError(root);
        return root;
    }

    private Map<String, Object> parseBody(Http.Response response) throws ApiException {
        Map<String, Object> root = MiniJson.asObject(MiniJson.parseQuietly(response.body));
        if (root == null) {
            throw new ApiException(0, "服务返回的不是 JSON");
        }
        return root;
    }

    /** 业务错误也可能藏在 200 里：{"success":false,"errorCode":1,"errorMessage":"..."}。 */
    private void checkBusinessError(String body) throws ApiException {
        if (body == null) {
            return;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return;
        }
        checkBusinessError(MiniJson.asObject(MiniJson.parseQuietly(trimmed)));
    }

    private void checkBusinessError(Map<String, Object> root) throws ApiException {
        if (root == null) {
            return;
        }
        int errorCode = (int) MiniJson.lng(root, "errorCode", 0);
        if (errorCode != 0 && !MiniJson.bool(root, "success", false)) {
            String message = MiniJson.str(root, "errorMessage");
            throw new ApiException(errorCode, message == null || message.isEmpty()
                    ? "服务返回错误 " + errorCode : message);
        }
    }

    private void checkStatus(Http.Response response) throws ApiException {
        if (response.code == 200) {
            return;
        }
        String hint;
        switch (response.code) {
            case 401:
                hint = "服务需要登录或鉴权";
                break;
            case 403:
                hint = "鉴权失败";
                String detail = authDetail(response.errorMessage);
                if (!detail.isEmpty()) {
                    hint += "：" + detail;
                }
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

    /** 把官方 X-Error-Message 翻译成能指导用户的中文。 */
    private static String authDetail(String serverMessage) {
        if (serverMessage == null || serverMessage.trim().isEmpty()) {
            return "";
        }
        String lower = serverMessage.toLowerCase(Locale.ROOT);
        if (lower.contains("missing")) {
            return "这个服务需要 AppId / AppSecret";
        }
        if (lower.contains("timestamp")) {
            return "手机时间不准，请开启自动校准时间";
        }
        if (lower.contains("signature")) {
            return "签名不匹配，检查 AppSecret，或校准手机时间";
        }
        if (lower.contains("appsecret")) {
            return "AppSecret 无效";
        }
        if (lower.contains("appid")) {
            return "AppId 无效";
        }
        return serverMessage;
    }

    private void requireConfigured() throws ApiException {
        if (endpoint.isEmpty()) {
            throw new ApiException(0, "还没有填写弹幕服务地址");
        }
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

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
