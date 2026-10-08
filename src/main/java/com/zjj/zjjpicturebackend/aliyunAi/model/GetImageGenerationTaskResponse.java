package com.zjj.zjjpicturebackend.aliyunAi.model;

import cn.hutool.core.annotation.Alias;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 查询图像生成任务响应
 * <p>
 * 生成结果在 output.choices[0].message.content[0].image，可用 {@link #extractImageUrl()} 直接取
 */
@Data
public class GetImageGenerationTaskResponse implements Serializable {

    /**
     * 任务输出信息
     */
    private Output output;

    /**
     * 本次调用的资源计量信息（图片输入输出计量，不是 token 用量）
     */
    private Usage usage;

    /**
     * 请求唯一标识
     */
    @Alias("request_id")
    private String requestId;

    /**
     * 错误码，仅请求失败时返回
     */
    private String code;

    /**
     * 错误信息，仅请求失败时返回
     */
    private String message;

    private static final long serialVersionUID = 1L;

    @Data
    public static class Output implements Serializable {

        /**
         * 任务 ID，24 小时内可查询
         */
        @Alias("task_id")
        private String taskId;

        /**
         * 任务状态：PENDING / RUNNING / SUCCEEDED / FAILED / CANCELED / UNKNOWN
         */
        @Alias("task_status")
        private String taskStatus;

        /**
         * 任务提交时间（UTC+8）
         */
        @Alias("submit_time")
        private String submitTime;

        /**
         * 任务调度时间（UTC+8）
         */
        @Alias("scheduled_time")
        private String scheduledTime;

        /**
         * 任务结束时间（UTC+8）
         */
        @Alias("end_time")
        private String endTime;

        /**
         * 提示词改写状态
         */
        @Alias("rewrite_status")
        private String rewriteStatus;

        /**
         * 结果列表
         */
        private List<Choice> choices;

        /**
         * 错误码，任务失败时返回
         */
        private String code;

        /**
         * 错误信息，任务失败时返回
         */
        private String message;

        private static final long serialVersionUID = 1L;
    }

    @Data
    public static class Choice implements Serializable {

        /**
         * 任务停止原因，正常完成为 stop
         */
        @Alias("finish_reason")
        private String finishReason;

        /**
         * 模型返回的消息
         */
        private Message message;

        private static final long serialVersionUID = 1L;
    }

    @Data
    public static class Message implements Serializable {

        /**
         * 角色，固定为 assistant
         */
        private String role;

        /**
         * 生成结果内容
         */
        private List<ContentItem> content;

        private static final long serialVersionUID = 1L;
    }

    @Data
    public static class ContentItem implements Serializable {

        /**
         * 生成图片的 URL，格式为 PNG，链接 24 小时内有效
         */
        private String image;

        /**
         * 内容类型，图片为 image
         */
        private String type;

        private static final long serialVersionUID = 1L;
    }

    @Data
    public static class Usage implements Serializable {

        // ---------- qwen-image-3.0 系列 ----------
        @Alias("output_width")
        private Integer outputWidth;

        @Alias("output_height")
        private Integer outputHeight;

        @Alias("input_image_count")
        private Integer inputImageCount;

        @Alias("input_image_type")
        private String inputImageType;

        @Alias("output_image_count")
        private Integer outputImageCount;

        @Alias("output_image_type")
        private String outputImageType;

        // ---------- qwen-image-2.1-pro ----------
        @Alias("image_count")
        private Integer imageCount;

        private Integer width;

        private Integer height;

        private static final long serialVersionUID = 1L;
    }

    /**
     * 从结果中取出第一张生成图片的 URL
     * <p>
     * 故意不叫 getImageUrl，避免被当成 JSON 属性序列化出去
     *
     * @return 生成图片 URL，未完成或失败时返回 null
     */
    public String extractImageUrl() {
        if (output == null || CollUtil.isEmpty(output.getChoices())) {
            return null;
        }
        Choice choice = output.getChoices().get(0);
        if (choice == null || choice.getMessage() == null || CollUtil.isEmpty(choice.getMessage().getContent())) {
            return null;
        }
        for (ContentItem contentItem : choice.getMessage().getContent()) {
            if (contentItem != null && StrUtil.isNotBlank(contentItem.getImage())) {
                return contentItem.getImage();
            }
        }
        return null;
    }

    /**
     * 判断任务是否已执行成功
     */
    public boolean isSucceeded() {
        return output != null && "SUCCEEDED".equals(output.getTaskStatus());
    }

    /**
     * 判断任务是否已失败
     */
    public boolean isFailed() {
        return output != null && "FAILED".equals(output.getTaskStatus());
    }
}
