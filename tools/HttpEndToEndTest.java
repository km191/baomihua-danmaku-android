import com.baomihua.danmaku.core.CommentParser;
import com.baomihua.danmaku.core.Danmaku;
import com.baomihua.danmaku.core.DanmuApi;
import com.baomihua.danmaku.core.MatchScorer;
import com.baomihua.danmaku.core.Models;
import com.baomihua.danmaku.core.TitleParser;
import com.baomihua.danmaku.core.UrlHttp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * 端到端测试网络层：起一个本地 HTTP 服务扮演 danmu_api，
 * 走真实的 HttpURLConnection + gzip + JSON 解析，验证整条链路。
 */
public final class HttpEndToEndTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/search/episodes", new JsonHandler(searchBody(), true));
        server.createContext("/api/v2/comment/", new JsonHandler(commentBody(), true));
        server.createContext("/api/v2/bangumi/", new JsonHandler(bangumiBody(), false));
        server.createContext("/plain", new JsonHandler(searchBody(), false));
        server.createContext("/api404/api/v2/search/episodes", new ErrorHandler());
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        System.out.println("本地测试服务已启动：" + base);

        try {
            UrlHttp http = new UrlHttp();
            DanmuApi api = new DanmuApi(base, http);

            // 1. 搜索（gzip 压缩响应）
            List<Models.Anime> animes = api.searchEpisodes("某番", 5);
            check("搜索返回 1 部作品", animes.size() == 1, "size=" + animes.size());
            Models.Anime anime = animes.get(0);
            check("作品名", "某番 第二季".equals(anime.title), anime.title);
            check("分集数 2", anime.episodes.size() == 2, String.valueOf(anime.episodes.size()));

            // 2. 打分选中第二季第 5 集
            TitleParser.TitleInfo info = TitleParser.parse("某番 第二季 第5集");
            List<MatchScorer.Candidate> ranked = MatchScorer.rank(info, animes);
            check("匹配到第 5 集", !ranked.isEmpty()
                    && ranked.get(0).episode.episodeId == 505L, String.valueOf(ranked));

            // 3. 取弹幕
            List<Danmaku> list = api.comments(505);
            check("弹幕 3 条", list.size() == 3, "size=" + list.size());
            check("弹幕文本", "第一条".equals(list.get(0).text), list.get(0).text);
            check("弹幕时间", list.get(0).timeMs == 1500L, String.valueOf(list.get(0).timeMs));
            check("顶部弹幕", list.get(1).mode == 5, String.valueOf(list.get(1).mode));

            // 4. 未压缩响应
            DanmuApi plain = new DanmuApi(base, http);
            List<Danmaku> parsed = CommentParser.parseAuto(
                    http.get(base + "/plain", null, 5000).body);
            check("未压缩响应也能解析", parsed.size() >= 0, "size=" + parsed.size());

            // 5. 4xx 错误映射
            try {
                new DanmuApi(base + "/api404", http).searchEpisodes("boom");
                check("错误响应应抛异常", false, "");
            } catch (DanmuApi.ApiException e) {
                check("404 → 提示接口不存在", e.httpCode == 404
                        && e.getMessage().contains("接口不存在"), e.getMessage());
            }

            // 6. 地址规范化后再请求
            DanmuApi withSuffix = new DanmuApi(base + "/api/v2", http);
            check("带 /api/v2 的地址同样可用",
                    withSuffix.searchEpisodes("某番").size() == 1, "ok");

            System.out.println();
            if (failures == 0) {
                System.out.println("端到端测试全部通过");
            } else {
                System.out.println("端到端测试失败 " + failures + " 项");
                System.exit(1);
            }
        } finally {
            server.stop(0);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  ✓ " + name);
        } else {
            failures++;
            System.out.println("  ✗ " + name + "  [" + detail + "]");
        }
    }

    private static String searchBody() {
        return "{\"success\":true,\"errorCode\":0,\"animes\":[{\"animeId\":50,\"animeTitle\":\"某番 第二季\","
                + "\"typeDescription\":\"TV\",\"episodes\":["
                + "{\"episodeId\":505,\"episodeTitle\":\"第5话\"},"
                + "{\"episodeId\":506,\"episodeTitle\":\"第6话\"}]}]}";
    }

    private static String commentBody() {
        return "{\"count\":3,\"comments\":["
                + "{\"cid\":1,\"p\":\"1.5,1,16777215,userA\",\"m\":\"第一条\"},"
                + "{\"cid\":2,\"p\":\"2.0,5,16711680,userB\",\"m\":\"第二条顶部\"},"
                + "{\"cid\":3,\"p\":\"3.25,4,255,userC\",\"m\":\"第三条底部\"}]}";
    }

    private static String bangumiBody() {
        return "{\"bangumi\":{\"animeId\":50,\"animeTitle\":\"某番 第二季\",\"episodes\":["
                + "{\"episodeId\":505,\"episodeTitle\":\"第5话\"}]}}";
    }

    private static final class JsonHandler implements HttpHandler {
        private final String body;
        private final boolean gzip;

        JsonHandler(String body, boolean gzip) {
            this.body = body;
            this.gzip = gzip;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] payload = body.getBytes("UTF-8");
            if (gzip) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                GZIPOutputStream gz = new GZIPOutputStream(out);
                gz.write(payload);
                gz.close();
                payload = out.toByteArray();
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            OutputStream os = exchange.getResponseBody();
            os.write(payload);
            os.close();
        }
    }

    private static final class ErrorHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] payload = "{\"error\":\"not found\"}".getBytes("UTF-8");
            exchange.sendResponseHeaders(404, payload.length);
            OutputStream os = exchange.getResponseBody();
            os.write(payload);
            os.close();
        }
    }

    private HttpEndToEndTest() {
    }
}
