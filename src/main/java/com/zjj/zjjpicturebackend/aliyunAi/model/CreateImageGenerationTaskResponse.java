package com.zjj.zjjpicturebackend.aliyunAi.model;

import cn.hutool.core.annotation.Alias;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建图像生成任务响应
 * <p>
 * 成功：{"output":{"task_status":"PENDING","task_id":"..."},"request_id":"..."}
 * 失败：{"code":"InvalidApiKey","message":"...","request_id":"..."}
 */
@Data
public class CreateImageGenerationTaskResponse implements Serializable {

    /**
     * 任务受理信息
     */
    private Output output;

    /**
     * 请求唯一标识，用于溯源与排查
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
         * 异步任务 ID，用于后续轮询查询任务状态与结果
         */
        @Alias("task_id")
        private String taskId;

        /**
         * 任务状态，刚提交时通常为 PENDING
         */
        @Alias("task_status")
        private String taskStatus;

        private static final long serialVersionUID = 1L;
    }
}
