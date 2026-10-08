package com.zjj.zjjpicturebackend.aliyunAi;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.zjj.zjjpicturebackend.aliyunAi.model.CreateImageGenerationTaskRequest;
import com.zjj.zjjpicturebackend.aliyunAi.model.CreateImageGenerationTaskResponse;
import com.zjj.zjjpicturebackend.aliyunAi.model.GetImageGenerationTaskResponse;
import com.zjj.zjjpicturebackend.exception.BusinessException;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.exception.ThrowUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云百炼（DashScope）图像生成 API
 * <p>
 * 采用异步调用：先创建任务拿到 taskId，再轮询查询任务状态
 * 文生图与图生图共用同一套接口，区别只在请求体里是否带参考图
 */
@Slf4j
@Component
public class AliYunAiApi {

    /**
     * 百炼 API Key
     */
    @Value("${aliYunAi.api-key:}")
    private String apiKey;

    /**
     * 可选，工作空间专属域名，形如 https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com
     * 留空则使用通用域名
     */
    @Value("${aliYunAi.base-url:}")
    private String baseUrl;

    /**
     * 默认模型
     */
    @Value("${aliYunAi.model:qwen-image-3.0}")
    private String defaultModel;

    /**
     * 通用域名（官方文档说明与工作空间专属域名功能一致）
     */
    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

    /**
     * 创建图像生成任务（异步）
     */
    public static final String CREATE_IMAGE_GENERATION_TASK_PATH = "/api/v1/services/aigc/image-generation/generation";

    /**
     * 查询任务结果（异步）
     */
    public static final String GET_TASK_PATH = "/api/v1/tasks/%s";

    /**
     * 创建图像生成任务
     *
     * @param createImageGenerationTaskRequest 创建任务请求
     * @return 任务受理信息（含 taskId）
     */
    public CreateImageGenerationTaskResponse createImageGenerationTask(CreateImageGenerationTaskRequest createImageGenerationTaskRequest) {
        ThrowUtils.throwIf(createImageGenerationTaskRequest == null, ErrorCode.PARAMS_ERROR, "生图参数为空");
        ThrowUtils.throwIf(StrUtil.isBlank(apiKey), ErrorCode.OPERATION_ERROR, "未配置阿里云百炼 API Key");
        // 未显式指定模型时，使用配置里的默认模型
        if (StrUtil.isBlank(createImageGenerationTaskRequest.getModel())) {
            createImageGenerationTaskRequest.setModel(defaultModel);
        }
        String url = getBaseUrl() + CREATE_IMAGE_GENERATION_TASK_PATH;
        // 显式忽略空值：文生图时 content 里不能出现 image: null，否则百炼会直接返回 400
        String requestBody = JSONUtil.toJsonStr(JSONUtil.parseObj(createImageGenerationTaskRequest,
                JSONConfig.create().setIgnoreNullValue(true)));
        HttpRequest httpRequest = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                // 必须开启异步处理，缺少该请求头会报 current user api does not support synchronous calls
                .header("X-DashScope-Async", "enable")
                .header("Content-Type", "application/json")
                .body(requestBody);
        // 处理响应
        try (HttpResponse httpResponse = httpRequest.execute()) {
            if (!httpResponse.isOk()) {
                log.error("创建生图任务请求异常，httpStatus={}，body={}", httpResponse.getStatus(), httpResponse.body());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 生图失败");
            }
            CreateImageGenerationTaskResponse response = JSONUtil.toBean(httpResponse.body(), CreateImageGenerationTaskResponse.class);
            if (StrUtil.isNotBlank(response.getCode())) {
                log.error("创建生图任务失败，code={}，message={}", response.getCode(), response.getMessage());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 生图失败，" + response.getMessage());
            }
            return response;
        }
    }

    /**
     * 查询图像生成任务结果
     *
     * @param taskId 任务 ID
     * @return 任务状态与结果
     */
    public GetImageGenerationTaskResponse getImageGenerationTask(String taskId) {
        ThrowUtils.throwIf(StrUtil.isBlank(taskId), ErrorCode.PARAMS_ERROR, "任务 ID 不能为空");
        String url = String.format(getBaseUrl() + GET_TASK_PATH, taskId);
        try (HttpResponse httpResponse = HttpRequest.get(url)
                .header("Authorization", "Bearer " + apiKey)
                .execute()) {
            if (!httpResponse.isOk()) {
                log.error("查询生图任务请求异常，httpStatus={}，body={}", httpResponse.getStatus(), httpResponse.body());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取生图任务结果失败");
            }
            return JSONUtil.toBean(httpResponse.body(), GetImageGenerationTaskResponse.class);
        }
    }

    /**
     * 获取请求域名：配置了工作空间专属域名就用它，否则回落到通用域名
     */
    private String getBaseUrl() {
        if (StrUtil.isBlank(baseUrl)) {
            return DEFAULT_BASE_URL;
        }
        return StrUtil.removeSuffix(baseUrl.trim(), "/");
    }
}
