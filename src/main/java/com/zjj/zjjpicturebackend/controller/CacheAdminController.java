package com.zjj.zjjpicturebackend.controller;

import cn.hutool.core.util.StrUtil;
import com.zjj.zjjpicturebackend.annotation.AuthCheck;
import com.zjj.zjjpicturebackend.common.BaseResponse;
import com.zjj.zjjpicturebackend.common.ResultUtils;
import com.zjj.zjjpicturebackend.constant.UserConstant;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.exception.ThrowUtils;
import com.zjj.zjjpicturebackend.service.CacheRefreshService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/cache")
@RequiredArgsConstructor
public class CacheAdminController {

    private final CacheRefreshService cacheRefreshService;

    @PostMapping("/refresh/all")
    public BaseResponse<Boolean> refreshAll() {
        cacheRefreshService.refreshAll();
        return ResultUtils.success(true);
    }

    @PostMapping("/refresh/prefix")
    public BaseResponse<Boolean> refreshByPrefix(@RequestParam String prefix) {
        // 加个校验，防止误传空前缀导致全量清理
        ThrowUtils.throwIf(StrUtil.isBlank(prefix), ErrorCode.PARAMS_ERROR);
        cacheRefreshService.refreshByPrefix(prefix);
        return ResultUtils.success(true);
    }

    @PostMapping("/refresh/key")
    public BaseResponse<Boolean> refreshByKey(@RequestParam String cacheKey) {
        ThrowUtils.throwIf(StrUtil.isBlank(cacheKey), ErrorCode.PARAMS_ERROR);
        cacheRefreshService.refreshByKey(cacheKey);
        return ResultUtils.success(true);
    }
}