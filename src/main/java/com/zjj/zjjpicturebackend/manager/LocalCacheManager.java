package com.zjj.zjjpicturebackend.manager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class LocalCacheManager {

    // 保留你原来的 Caffeine 配置，这里假设最大 10000 条，写入后 10 分钟过期（兜底）
    private final Cache<String,String> LOCAL_CACHE= Caffeine.newBuilder().initialCapacity(1024)
            .maximumSize(10000L)
            .expireAfterWrite(5L, TimeUnit.MINUTES)
            .build();

    // 业务侧统一走这里
    public String get(String key) {
        return LOCAL_CACHE.getIfPresent(key);
    }

    public void put(String key, String value) {
        LOCAL_CACHE.put(key, value);
    }

    // 清除单个 key
    public void invalidate(String key) {
        LOCAL_CACHE.invalidate(key);
    }

    // 按前缀清除（Caffeine 没有原生前缀删除，需要遍历）
    public void invalidateByPrefix(String prefix) {
        LOCAL_CACHE.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    // 清空所有
    public void invalidateAll() {
        LOCAL_CACHE.invalidateAll();
    }
}