package com.zjj.zjjpicturebackend.manager;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheRefreshMessageListener implements MessageListener {

    private final LocalCacheManager localCacheManager;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("[CacheRefresh] 收到刷新消息: {}", body);
        try {
            CacheRefreshMessage msg = JSONUtil.toBean(body, CacheRefreshMessage.class);
            if (msg == null || msg.getType() == null) {
                return;
            }
            switch (msg.getType()) {
                case "ALL":
                    localCacheManager.invalidateAll();
                    break;
                case "PREFIX":
                    localCacheManager.invalidateByPrefix(msg.getTarget());
                    break;
                case "KEY":
                    localCacheManager.invalidate(msg.getTarget());
                    break;
                default:
                    log.warn("[CacheRefresh] 未知的刷新类型: {}", msg.getType());
            }
            log.info("[CacheRefresh] 本地缓存清理完成, type={}, target={}", msg.getType(), msg.getTarget());
        } catch (Exception e) {
            log.error("[CacheRefresh] 处理刷新消息失败", e);
        }
    }
}