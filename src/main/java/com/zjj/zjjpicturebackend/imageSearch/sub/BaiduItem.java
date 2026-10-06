package com.zjj.zjjpicturebackend.imageSearch.sub;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个搜索结果项
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BaiduItem {
    /**
     * 图片标题
     */
    private String title;

    /**
     * 缩略图 URL
     */
    private String thumbnail;

    /**
     * 来源网页 URL
     */
    private String url;

    /**
     * 从 JsonNode 解析并创建 BaiduItem
     * 处理了百度 API 返回字段不一致的问题
     */
    public static BaiduItem fromJson(JsonNode data) {
        String title = "";
        if (data.has("title") && data.get("title").isArray() && !data.get("title").isEmpty()) {
            title = data.get("title").get(0).asText();
        }
        
        String thumbnail = "";
        if (data.has("image_src")) thumbnail = data.get("image_src").asText();
        else if (data.has("thumbUrl")) thumbnail = data.get("thumbUrl").asText();

        String url = "";
        if (data.has("url")) url = data.get("url").asText();
        else if (data.has("fromUrl")) url = data.get("fromUrl").asText();

        return new BaiduItem(title, thumbnail, url);
    }
}
