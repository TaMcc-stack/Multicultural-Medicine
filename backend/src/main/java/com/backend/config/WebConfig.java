package com.backend.config;

import com.backend.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Web 配置：CORS + 登录拦截 + 强制 JSON 响应字符集为 UTF-8
 *
 * <p>Spring 默认的 MappingJackson2HttpMessageConverter 写出
 * {@code Content-Type: application/json} 不带 charset，浏览器会按 Latin-1 解码导致中文变乱码。
 * 这里显式把默认字符集设为 UTF-8，使响应头变成
 * {@code Content-Type: application/json;charset=UTF-8}。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/health",
                        // 知识库整体需要登录——此前是 /api/kb/** 全放行，等于任何人（含未登录）
                        // 都能上传 / 列出 / 删除 / 下载任意文档，且没有归属概念。
                        // 只留两个「由浏览器直接发起、带不了 Authorization 头」的只读端点：
                        //   /files/{id}           window.open 打开原文件
                        //   /docs/{id}/page/{n}   <img src> 加载 PDF 原页图
                        // 放行范围必须精确到具体路径，不能写成 /**（那会把上传、删除也放出去）。
                        // 与下面头像那条同理。
                        "/api/kb/files/*",
                        "/api/kb/docs/*/page/*",
                        // 只放行「读取头像」这一个 GET：<img src> 无法携带 Authorization 头。
                        // 注意是 /avatar/* 而不是 /avatar/**——后者会把上传接口 POST /avatar
                        // 也放行，那里拿不到注入的 userId 会直接 NPE。
                        "/api/auth/avatar/*"
                );
    }

    @Override
    public void extendMessageConverters(@NonNull List<HttpMessageConverter<?>> converters) {
        // 强制 JSON 响应携带 charset=UTF-8，避免前端用 Latin-1 解码后中文乱码
        // 注意：只有把 charset 写进 MediaType 才能真正影响响应头，
        // 仅 setDefaultCharset() 只会改写入字节流，不影响 Content-Type 头。
        MediaType jsonUtf8 = new MediaType("application", "json", StandardCharsets.UTF_8);
        for (HttpMessageConverter<?> conv : converters) {
            if (conv instanceof MappingJackson2HttpMessageConverter jackson) {
                jackson.setDefaultCharset(StandardCharsets.UTF_8);
                jackson.setSupportedMediaTypes(Collections.singletonList(jsonUtf8));
            } else if (conv instanceof StringHttpMessageConverter str) {
                str.setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }
}
