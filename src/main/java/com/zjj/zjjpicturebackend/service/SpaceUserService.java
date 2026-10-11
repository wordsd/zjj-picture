package com.zjj.zjjpicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjj.zjjpicturebackend.model.dto.spaceuser.SpaceUser;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zjj.zjjpicturebackend.model.dto.spaceuser.SpaceUserAddRequest;
import com.zjj.zjjpicturebackend.model.dto.spaceuser.SpaceUserQueryRequest;
import com.zjj.zjjpicturebackend.model.vo.SpaceUserVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
* @author Administrator
* @description 针对表【space_user(空间用户关联)】的数据库操作Service
* @createDate 2026-10-09 14:17:28
*/
public interface SpaceUserService extends IService<SpaceUser> {
    /**
     * 添加空间用户
     * @param spaceUserAddRequest
     * @return
     */
    long addSpaceUser(SpaceUserAddRequest spaceUserAddRequest);

    /**
     * 获取空间用户封装类
     * @param spaceUser
     * @param request
     * @return
     */
    SpaceUserVO getSpaceUserVO(SpaceUser spaceUser, HttpServletRequest request);

    /**
     * 获取空间封装列表
     * @param spaceUserList
     * @return
     */
    List<SpaceUserVO> getSpaceUserVOList(List<SpaceUser> spaceUserList);

    /**
     * 校验空间用户
     * @param spaceUser
     * @param add
     */
    void validSpaceUser(SpaceUser spaceUser, boolean add);

    /**
     * 构造查询条件
     * @param spaceUserQueryRequest
     * @return
     */
    QueryWrapper<SpaceUser> getQueryWrapper(SpaceUserQueryRequest spaceUserQueryRequest);
}
