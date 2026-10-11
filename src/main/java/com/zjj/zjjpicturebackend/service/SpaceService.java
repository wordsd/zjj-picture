package com.zjj.zjjpicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjj.zjjpicturebackend.model.dto.space.*;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zjj.zjjpicturebackend.model.entity.User;
import com.zjj.zjjpicturebackend.model.vo.SpaceVO;
import jakarta.servlet.http.HttpServletRequest;

/**
* @author Administrator
* @description 针对表【space(空间)】的数据库操作Service
* @createDate 2026-09-16 14:38:54
*/
public interface SpaceService extends IService<Space> {

    /**
     * 创建空间
     *
     * @param spaceAddRequest 创建请求
     * @param loginUser       当前登录用户
     * @return 新空间 id
     */
    long addSpace(SpaceAddRequest spaceAddRequest, User loginUser);

    /**
     * 更新空间（管理员可用，可以修改任意字段）
     *
     * @param spaceUpdateRequest 更新请求
     * @param loginUser          当前登录用户
     */
    void updateSpace(SpaceUpdateRequest spaceUpdateRequest, User loginUser);

    /**
     * 编辑空间（用户侧，仅允许改空间名）
     *
     * @param spaceEditRequest 编辑请求
     * @param loginUser        当前登录用户
     */
    void editSpace(SpaceEditRequest spaceEditRequest, User loginUser);

    /**
     * 删除空间
     *
     * @param id        空间 id
     * @param loginUser 当前登录用户
     */
    void deleteSpace(long id, User loginUser);

    /**
     * 获取封装空间信息（单条）
     *
     * @param space 空间实体
     * @return 空间 VO
     */
    SpaceVO getSpaceVO(Space space, HttpServletRequest request);

    /**
     * 分页获取空间封装信息
     *
     * @param spacePage 空间分页实体
     * @return 空间 VO 分页
     */
    Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage);

    /**
     * 获取查询条件
     *
     * @param spaceQueryRequest 查询请求
     * @return QueryWrapper
     */
    QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest);

    /**
     * 空间校验
     *
     * @param space 空间实体
     * @param add   是否为新增场景
     */
    void validSpace(Space space, boolean add);

    /**
     * 根据空间级别填充上限参数（maxSize、maxCount）
     *
     * @param space 空间实体
     */
    void fillSpaceBySpaceLevel(Space space);
    /**
     * 校验空间权限
     *
     * @param loginUser 当前登录用户
     * @param space     空间实体
     */
    void checkSpaceAuth(User loginUser, Space space);
}
