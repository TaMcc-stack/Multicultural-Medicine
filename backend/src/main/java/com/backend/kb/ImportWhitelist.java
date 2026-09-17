package com.backend.kb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「从链接导入」的域名白名单。
 *
 * <p>配置格式：{@code 域名:显示名}，逗号分隔。显示名会成为该文档的「来源机构」
 * （{@code source_org}）——比 trafilatura 猜出来的 sitename 干净得多
 * （实测它会返回「人民网版权所有」这种带噪音的值）。</p>
 *
 * <p><b>为什么必须在发起请求之前校验</b>：抓取方是服务端，若允许任意 URL，
 * 攻击者就能让服务器去请求内网地址（SSRF）——比如 {@code http://127.0.0.1:8080/...}
 * 或云环境的元数据接口。所以这里是服务端请求的**唯一放行口**。</p>
 *
 * <p>白名单只做域名匹配（含子域），不解析 DNS——域名解析会引入 DNS 重绑定问题，
 * 那是另一层防护（fetch 服务里另有一道内网字面量拦截）。</p>
 */
@Component
public class ImportWhitelist {

    private final Map<String, String> byDomain;

    public ImportWhitelist(@Value("${app.kb.import-whitelist:}") String raw) {
        this.byDomain = parse(raw);
    }

    /** 解析 "people.com.cn:人民网,xinhuanet.com:新华网" 形式；单项写错只跳过该项 */
    private static Map<String, String> parse(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String item : raw.split(",")) {
            String s = item.trim();
            if (s.isEmpty()) continue;
            int i = s.indexOf(':');
            String domain = (i > 0 ? s.substring(0, i) : s).trim().toLowerCase();
            String name = (i > 0 ? s.substring(i + 1) : s).trim();
            if (!domain.isEmpty()) out.put(domain, name.isEmpty() ? domain : name);
        }
        return out;
    }

    /**
     * 该 URL 是否允许导入；允许则返回来源机构显示名，否则返回 null。
     *
     * <p>域名匹配规则：主机名等于白名单域名，**或**是其子域
     * （{@code people.com.cn} 放行 {@code health.people.com.cn} 和 {@code www.people.com.cn}）。</p>
     */
    public String displayNameFor(String url) {
        String host = hostOf(url);
        if (host == null) return null;
        for (Map.Entry<String, String> e : byDomain.entrySet()) {
            String d = e.getKey();
            if (host.equals(d) || host.endsWith("." + d)) return e.getValue();
        }
        return null;
    }

    /** 取主机名；非 http(s) 或无法解析时返回 null（一律视为不在白名单） */
    private static String hostOf(String url) {
        if (url == null) return null;
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme();
            if (scheme == null) return null;
            String s = scheme.toLowerCase();
            if (!"http".equals(s) && !"https".equals(s)) return null;
            String host = u.getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    /** 白名单里的域名清单，用于错误提示里告诉管理员「哪些能导」 */
    public String describe() {
        return byDomain.isEmpty() ? "（未配置）" : String.join("、", byDomain.keySet());
    }

    public boolean isEmpty() {
        return byDomain.isEmpty();
    }
}
