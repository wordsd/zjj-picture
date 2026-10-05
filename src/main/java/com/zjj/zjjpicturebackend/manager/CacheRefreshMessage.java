package com.zjj.zjjpicturebackend.manager;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheRefreshMessage implements Serializable {
    /**
     * 刷新类型：ALL / PREFIX / KEY
     */
    private String type;
    /**
     * 目标值：
     * - ALL：null
     * - PREFIX：前缀，如 "zjjPicture:listPictureVOByPage:"
     * - KEY：完整的 cacheKey
     */
    private String target;
    /**
     * 广播时间戳（可选，用于日志排查）
     */
    private long timestamp;
}