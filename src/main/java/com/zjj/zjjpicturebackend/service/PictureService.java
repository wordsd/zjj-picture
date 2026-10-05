package com.zjj.zjjpicturebackend.service;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjj.zjjpicturebackend.model.dto.picture.PictureQueryRequest;
import com.zjj.zjjpicturebackend.model.dto.picture.PictureReviewRequest;
import com.zjj.zjjpicturebackend.model.dto.picture.PictureUpLoadRequest;
import com.zjj.zjjpicturebackend.model.dto.picture.PictureUploadByBatchRequest;
import com.zjj.zjjpicturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zjj.zjjpicturebackend.model.entity.User;
import com.zjj.zjjpicturebackend.model.vo.PictureVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
* @author Administrator
* @description 针对表【picture(图片)】的数据库操作Service
* @createDate 2026-08-28 10:09:27
*/
public interface PictureService extends IService<Picture> {
    /**
     * 上传图片
     * @param pictureUpLoadRequest
     * @param loginUser
     * @return
     */
    PictureVO uploadPicture(Object inputSource, PictureUpLoadRequest pictureUpLoadRequest, User loginUser);

    /**
     * 删除图片
     * @param oldPicture
     */
    void clearPicture(Picture oldPicture);

    /**
     * 分页获取图片封装信息
     * @param picturePage
     * @param request
     * @return
     */
    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);

    /**
     * 批量抓取和创建图片
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return
     */
    Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest,User loginUser);
    /**
     * 审核图片
     * @param pictureReviewRequest
     * @param loginUser
     */
    void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser);
    /**
     * 获取查询条件
     * @param pictureQueryRequest
     * @return
     */
    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);

    /**
     * 获取封装图片信息
     * @param picture
     * @param request
     * @return
     */
    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    /**
     * 图片校验
     * @param picture
     */
    void validPicture(Picture picture);

    /**
     * 填充审核参数
     * @param picture
     * @param loginUser
     */
    void fillReviewParams(Picture picture, User loginUser);

    void checkPictureAuth(User loginUser, Picture picture);
}
