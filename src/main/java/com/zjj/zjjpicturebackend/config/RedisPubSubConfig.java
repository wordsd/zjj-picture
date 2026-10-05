package com.zjj.zjjpicturebackend.config;

import com.zjj.zjjpicturebackend.manager.CacheRefreshMessageListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisPubSubConfig {

    public static final String CACHE_REFRESH_CHANNEL = "cache:refresh:channel";

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheRefreshMessageListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 订阅缓存刷新频道
        container.addMessageListener(listener, new ChannelTopic(CACHE_REFRESH_CHANNEL));
        return container;
    }
}