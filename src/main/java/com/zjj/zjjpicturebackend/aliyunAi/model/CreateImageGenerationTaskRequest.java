package com.zjj.zjjpicturebackend.aliyunAi.model;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 创建图像生成任务请求（阿里云百炼 DashScope 异步协议）
 * <p>
 * 对应接口：POST /api/v1/services/aigc/image-generation/generation
 * 文生图与图生图共用同一接口，区别只在 content 里是否带 image
 */
@Data
public class CreateImageGenerationTaskRequest implements Serializable {

    /**
     * 模型，可选值：qwen-image-3.0-pro、qwen-image-3.0、qwen-image-2.1-pro
     */
    private String model;

    /**
     * 输入内容
     */
    private Input input;

    /**
     * 生成参数
     */
    private Parameters parameters;

    private static final long serialVersionUID = 1L;

    @Data
    public static class Input implements Serializable {

        /**
         * 消息数组，只支持单轮对话，因此只能放 1 个元素
         */
        private List<Message> messages;

        private static final long serialVersionUID = 1L;
    }

    @Data
    public static class Message implements Serializable {

        /**
         * 角色，固定为 user
         */
        private String role = "user";

        /**
         * 内容数组：
         * - 文生图：只放 1 个 text
         * - 图生图：1~3 个 image（2.1-pro 支持 1~10 个）+ 1 个 text
         */
        private List<ContentItem> content;

        private static final long serialVersionUID = 1L;
    }

    @Data
    public static class ContentItem implements Serializable {

        /**
         * 提示词，与 image 二选一
         */
        private String text;

        /**
         * 参考图片的 URL 或 Base64，图生图时填写，文生图必须为 null
         */
        private String image;

        private static final long serialVersionUID = 1L;

        public static ContentItem ofText(String text) {
            ContentItem contentItem = new ContentItem();
            contentItem.setText(text);
            return contentItem;
        }

        public static ContentItem ofImage(String image) {
            ContentItem contentItem = new ContentItem();
            contentItem.setImage(image);
            return contentItem;
        }
    }

    @Data
    public static class Parameters implements Serializable {

        /**
         * 可选，是否开启提示词智能改写，默认 true
         */
        @Alias("prompt_extend")
        @JsonProperty("promptExtend")
        private Boolean promptExtend;

        /**
         * 可选，提示词改写方式：direct（默认，文生图/图生图均支持）、agent（仅文生图）
         */
        @Alias("prompt_extend_mode")
        @JsonProperty("promptExtendMode")
        private String promptExtendMode;

        /**
         * 可选，是否开启思考模式，默认 true，且要求 promptExtend 为 true
         */
        @Alias("enable_thinking")
        @JsonProperty("enableThinking")
        private Boolean enableThinking;

        /**
         * 可选，生成图片数量，取值范围 1~6，默认 1
         */
        private Integer n;

        /**
         * 可选，输出图片分辨率，格式为「宽*高」，例如 1024*1024
         * 注意：DashScope 协议用星号分隔，OpenAI 兼容协议用字母 x 分隔
         * 总像素需在 512*512 ~ 2048*2048 之间，宽高比 1:8 ~ 8:1
         */
        private String size;

        /**
         * 可选，反向提示词，仅 qwen-image-3.0 系列支持
         */
        @Alias("negative_prompt")
        @JsonProperty("negativePrompt")
        private String negativePrompt;

        /**
         * 可选，随机种子，取值范围 [0, 2147483647]
         */
        private Integer seed;

        /**
         * 可选，是否添加水印，默认 false
         */
        private Boolean watermark;

        private static final long serialVersionUID = 1L;
    }
}
