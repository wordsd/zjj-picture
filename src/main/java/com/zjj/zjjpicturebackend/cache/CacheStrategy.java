package com.zjj.zjjpicturebackend.cache;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zjj.zjjpicturebackend.common.BaseResponse;
import com.zjj.zjjpicturebackend.common.ResultUtils;
import com.zjj.zjjpicturebackend.model.dto.picture.PictureQueryRequest;
import com.zjj.zjjpicturebackend.model.entity.Picture;
import com.zjj.zjjpicturebackend.model.vo.PictureVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.DigestUtils;

import java.util.concurrent.TimeUnit;

/**
 * 缓存策略接口
 * 该接口定义了缓存策略的基本行为规范，所有具体的缓存策略实现类都需要实现此接口
 * 通过实现不同的缓存策略，可以灵活地控制缓存的行为和淘汰机制
 */
public interface CacheStrategy {
    BaseResponse<Page<PictureVO>> cache(PictureQueryRequest pictureQueryRequest, Long current, Long size, HttpServletRequest request);
}
