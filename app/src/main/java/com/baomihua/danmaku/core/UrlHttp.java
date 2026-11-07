package com.baomihua.danmaku.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 基于 HttpURLConnection 的实现：Android 与 JVM 都能用，零依赖。
 */
public final class UrlHttp implements Http {

    @Override
    public Response get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        return request("GET", url, null, headers, timeoutMs);
    }

    @Override
    public Response post(String url, String jsonBody, Map<String, String> headers, int timeoutMs)
            throws IOException {
        return request("POST", url, jsonBody, headers, timeoutMs);
    }

    private Response request(String method, String url, String body, Map<String, String> headers,
                             int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(Math.max(3000, Math.min(timeoutMs, 20000)));
            conn.setReadTimeout(Math.max(3000, Math.min(timeoutMs, 30000)));
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                byte[] payload = body.getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(payload.length);
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(payload);
                } finally {
                    os.close();
                }
            }
            int code = conn.getResponseCode();
            InputStream in;
            if (code >= 400) {
                in = conn.getErrorStream();
            } else {
                in = conn.getInputStream();
            }
            String content = readAll(in, conn.getContentEncoding());
            String errorMessage = conn.getHeaderField("X-Error-Message");
            return new Response(code, content, errorMessage);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readAll(InputStream raw, String encoding) throws IOException {
        if (raw == null) {
            return "";
        }
        InputStream in = raw;
        if (encoding != null && encoding.toLowerCase().contains("gzip")) {
            in = new GZIPInputStream(raw);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > DanmuApi.maxBody()) {
                    break;
                }
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
