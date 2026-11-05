package com.admin.service.ddns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Fixed public HTTPS endpoints only; never request a user supplied URL. */
@Component
public class DdnsRemote {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    protected JsonNode request(String url, String token, Map<String, Object> patch) {
        return exchange(url, token, patch == null ? "GET" : "PATCH", patch);
    }
    protected JsonNode exchange(String url, String token, String method, Map<String,Object> payload) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json");
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (payload != null) builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            else builder.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("远程服务返回 HTTP " + response.statusCode());
            JsonNode body = mapper.readTree(response.body());
            if (token != null && !body.path("success").asBoolean()) throw new IllegalStateException("Cloudflare 拒绝请求，请检查权限和记录");
            return body;
        } catch (IllegalStateException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("DNS 或 Cloudflare 请求失败/超时，请稍后重试"); }
    }
    private String base(String zone) { return "https://api.cloudflare.com/client/v4/zones/" + id(zone); }
    public static String id(String id) {
        if (id == null || !id.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("Zone ID 或记录 ID 格式无效");
        return id.toLowerCase(Locale.ROOT);
    }
    public String zone(String zone, String token) {
        return request(base(zone), token, null).path("result").path("name").asText();
    }
    public List<Map<String,Object>> records(String zone, String token) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode body = request(base(zone) + "/dns_records?type=A&per_page=100&page=" + page, token, null);
            for (JsonNode record : body.path("result")) result.add(recordView(record));
            if (page >= body.path("result_info").path("total_pages").asInt(1)) return result;
        }
        throw new IllegalStateException("A 记录超过 1000 条，请缩小 Zone 范围");
    }
    public JsonNode record(String zone, String record, String token) {
        return request(base(zone) + "/dns_records/" + id(record), token, null).path("result");
    }
    public void update(String zone, String record, String token, String ip) {
        request(base(zone) + "/dns_records/" + id(record), token, Map.of("content", ip));
    }
    private Map<String,Object> recordView(JsonNode record) {
        return Map.of("id", record.path("id").asText(), "name", record.path("name").asText(),
                "content", record.path("content").asText(), "proxied", record.path("proxied").asBoolean());
    }
    public List<JsonNode> namedRecords(String zone, String token, String name) {
        List<JsonNode> records = new ArrayList<>();
        for(int page=1;page<=10;page++) {
            JsonNode body=request(base(zone)+"/dns_records?name="+name+"&per_page=100&page="+page,token,null);
            body.path("result").forEach(records::add);
            if(page>=body.path("result_info").path("total_pages").asInt(1)) return records;
        }
        throw new IllegalStateException("目标记录过多，暂停同步");
    }
    public JsonNode create(String zone,String token,String name,String ip,String marker) {
        return exchange(base(zone)+"/dns_records",token,"POST",Map.of("type","A","name",name,"content",ip,"ttl",60,"proxied",false,"comment",marker)).path("result");
    }
    public void remove(String zone,String token,String record) {
        exchange(base(zone)+"/dns_records/"+id(record),token,"DELETE",null);
    }
    public String resolve(String hostname) {
        Set<String> ips=resolveAll(hostname);
        if (ips.size()!=1) throw new IllegalStateException("来源需解析到唯一公网 IPv4，保留原记录");
        return ips.iterator().next();
    }
    public Set<String> resolveAll(String hostname) {
        JsonNode body = request("https://dns.google/resolve?type=A&name=" + hostname, null, null);
        if (body.path("Status").asInt(-1) != 0) throw new IllegalStateException("来源域名解析失败，保留原记录");
        Set<String> ips = new HashSet<>();
        for (JsonNode answer : body.path("Answer")) {
            String ip = answer.path("data").asText();
            if (answer.path("type").asInt() == 1) {
                if (!publicIpv4(ip)) throw new IllegalStateException("来源包含非公网 IPv4，保留原记录");
                ips.add(ip);
            }
        }
        if (ips.isEmpty() || ips.size()>50) throw new IllegalStateException("来源无有效地址或超过50个地址，保留原记录");
        return ips;
    }
    public static boolean publicIpv4(String ip) {
        if (ip == null || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        int[] n = Arrays.stream(ip.split("\\.")).mapToInt(Integer::parseInt).toArray();
        for (int v : n) if (v > 255) return false;
        return n[0] > 0 && n[0] < 224 && n[0] != 10 && n[0] != 127
                && !(n[0] == 100 && n[1] >= 64 && n[1] <= 127)
                && !(n[0] == 169 && n[1] == 254) && !(n[0] == 172 && n[1] >= 16 && n[1] <= 31)
                && !(n[0] == 192 && (n[1] == 168 || n[1] == 0))
                && !(n[0] == 198 && (n[1] == 18 || n[1] == 19));
    }
}
