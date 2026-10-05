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
import com.zjj.zjjpicturebackend.service.PictureService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.DigestUtils;

import java.util.concurrent.TimeUnit;

public class LocalCache implements CacheStrategy{
    final Cache<String,String> LOCAL_CACHE= Caffeine.newBuilder().initialCapacity(1024)
            .maximumSize(10000L)
            .expireAfterWrite(5L, TimeUnit.MINUTES)
            .build();
    @Resource
    private PictureService pictureService;

    @Override
    public BaseResponse<Page<PictureVO>> cache(PictureQueryRequest pictureQueryRequest, Long current, Long size, HttpServletRequest request) {
        //构建缓存Key
        String queryCondition = JSONUtil.toJsonStr(pictureQueryRequest);
        String hashKey = DigestUtils.md5DigestAsHex(queryCondition.getBytes());
        String cacheKey = "zjjPicture:listPictureVOByPage:" + hashKey;
        //从本地缓存中查询
        String cacheValue = LOCAL_CACHE.getIfPresent(cacheKey);
        if (cacheValue != null) {
            Page<PictureVO> cachePage =  JSONUtil.toBean(cacheValue, Page.class);
            return ResultUtils.success(cachePage);
        }
        // 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        // 获取封装类
        Page<PictureVO> pictureVOPage = pictureService.getPictureVOPage(picturePage, request);
        //更新本地缓存
        cacheValue = JSONUtil.toJsonStr(pictureVOPage);
        LOCAL_CACHE.put(cacheKey,cacheValue);
        return ResultUtils.success(pictureVOPage);
    }

}
