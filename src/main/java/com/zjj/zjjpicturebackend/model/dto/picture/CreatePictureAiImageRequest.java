package com.zjj.zjjpicturebackend.model.dto.picture;

import com.zjj.zjjpicturebackend.aliyunAi.model.CreateImageGenerationTaskRequest;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建 AI 生图任务请求（前端传入）
 * <p>
 * pictureId 为空：文生图；pictureId 不为空：以该图为参考做图生图
 */
@Data
public class CreatePictureAiImageRequest implements Serializable {

    /**
     * 参考图片 id，可为空
     */
    private Long pictureId;

    /**
     * 提示词
     */
    private String prompt;

    /**
     * 生成参数
     */
    private CreateImageGenerationTaskRequest.Parameters parameters;

    /**
     * 模型，可不传，不传则使用配置里的默认模型
     */
    private String model;

    private static final long serialVersionUID = 1L;
}
