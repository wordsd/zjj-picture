package com.zjj.zjjpicturebackend.service;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjj.zjjpicturebackend.aliyunAi.model.CreateImageGenerationTaskResponse;
import com.zjj.zjjpicturebackend.model.dto.picture.*;
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
     * 更新图片的可编辑信息（名称、简介、分类、标签）
     * <p>
     * 不要直接用 updateById：picture 按 spaceId 分表，
     * 实体更新会把分片键 spaceId 拼进 SET 子句导致 ShardingSphere 拒绝执行
     *
     * @param pictureEditRequest 编辑请求
     * @param loginUser          当前登录用户
     * @return 是否更新成功
     */
    boolean updatePictureInfo(PictureEditRequest pictureEditRequest, User loginUser);

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
     * 颜色搜索图片
     * @param spaceId
     * @param picColor
     * @param loginUser
     * @return
     */
    List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser);

    /**
     * 批量编辑图片
     * @param pictureEditByBatchRequest
     * @param loginUser
     */
    void editPictureBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser);

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

    /**
     * 创建 AI 生图任务（文生图 / 图生图）
     * <p>
     * 请求里的 pictureId 为空表示文生图，不为空表示以该图为参考做图生图
     *
     * @param createPictureAiImageRequest 生图请求
     * @param loginUser                   当前登录用户
     * @return 任务受理信息（含 taskId）
     */
    CreateImageGenerationTaskResponse createPictureAiImageTask(CreatePictureAiImageRequest createPictureAiImageRequest, User loginUser);
}
