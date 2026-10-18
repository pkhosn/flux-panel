package com.admin.service;

import com.alibaba.fastjson2.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class NodeLatencyService {
    private static final long TTL = 15 * 60_000L;
    private final Map<String, Entry> cache = new HashMap<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static class Entry {
        long created = System.currentTimeMillis();
        String id;
        long polled;
        JSONObject data;
    }
    protected JSONObject request(String path, JSONObject body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("https://api.globalping.io/v1/" + path)).timeout(Duration.ofSeconds(8));
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("探测服务暂不可用或额度不足");
        return JSONObject.parseObject(response.body());
    }
    public synchronized JSONObject measure(String target, boolean run) {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().created >= TTL);
        Entry entry = cache.get(target);
        try {
            if (entry == null) {
                if (!run) return JSONObject.of("status", "idle");
                entry = new Entry(); cache.put(target, entry);
                JSONArray locations = new JSONArray();
                for (int asn : new int[]{4134, 4837, 9808}) locations.add(JSONObject.of("country", "CN", "asn", asn, "limit", 1));
                var started = request("measurements", JSONObject.of("type", "ping", "target", target,
                        "locations", locations, "measurementOptions", JSONObject.of("packets", 4)));
                entry.id = started.getString("id");
                if (entry.id == null || !entry.id.matches("[A-Za-z0-9]+")) throw new IllegalStateException("探测服务返回异常");
                entry.data = JSONObject.of("status", "pending", "target", target, "testedAt", entry.created);
            } else if ("pending".equals(entry.data.getString("status")) && now - entry.polled >= 2000) {
                entry.polled = now;
                JSONObject result = request("measurements/" + entry.id, null);
                if ("finished".equals(result.getString("status"))) entry.data = summarize(result, target, entry.created);
                else if (now - entry.created > 90_000) throw new IllegalStateException("探测任务超时，请稍后重试");
            }
            return entry.data;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            entry.data = JSONObject.of("status", "error", "message", "探测失败，请稍后重试", "target", target);
            // Errors cool down for one minute rather than occupying the full result TTL.
            entry.created = now - TTL + 60_000;
            return entry.data;
        }
    }
    public static JSONObject summarize(JSONObject result, String target, long time) {
        JSONArray rows = new JSONArray();
        int[] asns = {4134, 4837, 9808};
        String[] names = {"电信", "联通", "移动"};
        JSONArray results = result.getJSONArray("results");
        for (int i = 0; i < asns.length; i++) {
            JSONObject row = JSONObject.of("name", names[i], "status", "unavailable");
            if (results != null) for (int j = 0; j < results.size(); j++) {
                JSONObject item = results.getJSONObject(j), probe = item.getJSONObject("probe");
                if (probe == null || !"CN".equals(probe.getString("country")) || probe.getIntValue("asn") != asns[i]) continue;
                JSONObject measured = item.getJSONObject("result");
                JSONObject stats = measured == null ? null : measured.getJSONObject("stats");
                row.put("city", probe.getString("city"));
                if (stats != null) {
                    row.put("loss", stats.get("loss")); row.put("avg", stats.get("avg"));
                    row.put("status", stats.get("avg") == null ? "timeout" : "ok");
                }
                break;
            }
            rows.add(row);
        }
        return JSONObject.of("status", "finished", "target", target, "testedAt", time, "results", rows);
    }
}
