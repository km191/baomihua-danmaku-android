package com.baomihua.danmaku.core;

import java.io.IOException;
import java.util.Map;

/**
 * 最小的 HTTP 抽象：Android 上用 HttpURLConnection，测试里可以塞假实现。
 */
public interface Http {

    final class Response {
        public final int code;
        public final String body;
        /** 服务端给出的错误说明（官方 API 会放在 X-Error-Message）。 */
        public final String errorMessage;

        public Response(int code, String body) {
            this(code, body, "");
        }

        public Response(int code, String body, String errorMessage) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }
    }

    Response get(String url, Map<String, String> headers, int timeoutMs) throws IOException;

    Response post(String url, String jsonBody, Map<String, String> headers, int timeoutMs) throws IOException;
}
