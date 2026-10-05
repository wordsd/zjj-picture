package com.zjj.zjjpicturebackend.test;

import com.zjj.zjjpicturebackend.manager.LocalCacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
class LocalCacheManagerTest {

    @Autowired
    private LocalCacheManager localCacheManager;

    @Test
    void testPutAndGet() {
        localCacheManager.put("key1", "value1");
        assertEquals("value1", localCacheManager.get("key1"));
    }

    @Test
    void testInvalidate() {
        localCacheManager.put("key1", "value1");
        localCacheManager.invalidate("key1");
        assertNull(localCacheManager.get("key1"));
    }

    @Test
    void testInvalidateByPrefix() {
        localCacheManager.put("zjjPicture:list:aaa", "v1");
        localCacheManager.put("zjjPicture:list:bbb", "v2");
        localCacheManager.put("zjjPicture:other:ccc", "v3");

        localCacheManager.invalidateByPrefix("zjjPicture:list:");

        assertNull(localCacheManager.get("zjjPicture:list:aaa"));
        assertNull(localCacheManager.get("zjjPicture:list:bbb"));
        // 前缀不匹配的不能被误删
        assertEquals("v3", localCacheManager.get("zjjPicture:other:ccc"));
    }

    @Test
    void testInvalidateAll() {
        localCacheManager.put("key1", "v1");
        localCacheManager.put("key2", "v2");
        localCacheManager.invalidateAll();
        assertNull(localCacheManager.get("key1"));
        assertNull(localCacheManager.get("key2"));
    }
}