package com.zjj.zjjpicturebackend.imageSearch.sub;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 百度以图搜图响应结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BaiduResponse {
    /**
     * 相似图片结果列表
     */
    private List<BaiduItem> raw;

    /**
     * 来源/原图结果列表 (通常为空，除非完全匹配)
     */
    private List<BaiduItem> exactMatches;

    /**
     * 百度结果页面的 URL
     */
    private String url;
}
