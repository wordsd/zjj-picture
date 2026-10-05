package com.zjj.zjjpicturebackend.cache;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjj.zjjpicturebackend.common.BaseResponse;
import com.zjj.zjjpicturebackend.model.dto.picture.PictureQueryRequest;
import com.zjj.zjjpicturebackend.model.vo.PictureVO;
import jakarta.servlet.http.HttpServletRequest;

public class strategyContext {
    private CacheStrategy cacheStrategy;
    public void setCacheStrategy(CacheStrategy cacheStrategy) {
        this.cacheStrategy = cacheStrategy;
    }
    public BaseResponse<Page<PictureVO>> cache(PictureQueryRequest pictureQueryRequest, Long current, Long size, HttpServletRequest request) {
        return cacheStrategy.cache(pictureQueryRequest,current,size,request);
    }
}
