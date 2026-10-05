package com.zjj.zjjpicturebackend.service.impl;

import cn.hutool.json.JSONUtil;
import com.zjj.zjjpicturebackend.config.RedisPubSubConfig;
import com.zjj.zjjpicturebackend.manager.CacheRefreshMessage;
import com.zjj.zjjpicturebackend.manager.LocalCacheManager;
import com.zjj.zjjpicturebackend.service.CacheRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheRefreshServiceimpl implements CacheRefreshService {

    private final StringRedisTemplate stringRedisTemplate;
    private final LocalCacheManager localCacheManager;

    /**
     * 刷新指定前缀的缓存（推荐用法，比如按业务模块刷新）
     */
    @Override
    public void refreshByPrefix(String prefix) {
        // 1. 删除 Redis 中所有匹配前缀的 key（生产环境建议用 SCAN，不要用 KEYS）
        Set<String> keys = scanKeys(prefix + "*");
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("[CacheRefresh] 已删除 Redis key 数量: {}", keys.size());
        }
        // 2. 清本节点的本地缓存（广播前先清一次，保证本机立刻生效）
        localCacheManager.invalidateByPrefix(prefix);
        // 3. 广播通知其他节点
        publish("PREFIX", prefix);
    }

    /**
     * 刷新指定 key
     */
    @Override
    public void refreshByKey(String cacheKey) {
        stringRedisTemplate.delete(cacheKey);
        localCacheManager.invalidate(cacheKey);
        publish("KEY", cacheKey);
    }

    /**
     * 刷新所有缓存（慎用）
     */
    @Override
    public void refreshAll() {
        Set<String> keys = scanKeys("zjjPicture:*");
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        localCacheManager.invalidateAll();
        publish("ALL", null);
    }

    private void publish(String type, String target) {
        CacheRefreshMessage message = new CacheRefreshMessage(type, target, System.currentTimeMillis());
        String json = JSONUtil.toJsonStr(message);
        stringRedisTemplate.convertAndSend(RedisPubSubConfig.CACHE_REFRESH_CHANNEL, json);
        log.info("[CacheRefresh] 已广播刷新消息: {}", json);
    }

    /**
     * 使用 SCAN 替代 KEYS，避免阻塞 Redis
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> result = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(200).build();
        try (Cursor<byte[]> cursor = stringRedisTemplate.executeWithStickyConnection(
                connection -> connection.scan(options))) {
            while (cursor.hasNext()) {
                result.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error("[CacheRefresh] SCAN 失败", e);
        }
        return result;
    }
}