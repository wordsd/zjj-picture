package com.zjj.zjjpicturebackend.service;

public interface CacheRefreshService {
    /**
     * 根据前缀刷新缓存
     * @param prefix
     */
    void refreshByPrefix(String prefix);

    /**
     * 根据key刷新缓存
     * @param cacheKey
     */
    void refreshByKey(String cacheKey);

    /**
     * 刷新所有缓存
     */
    void refreshAll();
}
