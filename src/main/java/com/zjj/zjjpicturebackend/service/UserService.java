package com.zjj.zjjpicturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjj.zjjpicturebackend.model.dto.user.*;
import com.zjj.zjjpicturebackend.model.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zjj.zjjpicturebackend.model.vo.LoginUserVO;
import com.zjj.zjjpicturebackend.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
* @author Administrator
* @description 针对表【user(用户)】的数据库操作Service
* @createDate 2026-08-25 16:42:11
*/
public interface UserService extends IService<User> {
    /**
     * 用户注册
     * @param userAccount 账号
     * @param userPassword 密码
     * @param checkPassword 确认密码
     * @param shareCode 邀请码
     * @return
     */
    long register(String userAccount, String userPassword,String checkPassword,String shareCode);

    /**
     * 用户登录
     * @param userAccount 账号
     * @param userPassword  密码
     * @return
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 用户注销（退出登录）
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);
    /**
     * 管理员新增用户
     * @param userAddRequest
     * @return
     */
    long addUser(UserAddRequest userAddRequest);
    /**
     * 管理员删除用户
     * @param id
     * @return
     */
    boolean deleteUser(long id);

    /**
     * 更新用户（管理员/本人）
     * @param userUpdateRequest
     * @return
     */
    boolean updateUser(UserUpdateRequest userUpdateRequest);

    /**
     * 获取用户VO
     */
    UserVO getUserVO(User user);

    /**
     * 分页获取用户封装VO
     */
    List<UserVO> getUserVOList(List<User> userList);
    /**
     * 使用兑换码兑换会员
     * @param vipExchangeRequest
     * @param loginUser 当前登录用户
     * @return 是否兑换成功
     */
    boolean exchangeVip(VipExchangeRequest vipExchangeRequest, User loginUser);

    /**
     * 当前登录用户修改自己个人信息
     */
    boolean updateMyInfo(UserMyInfoUpdateRequest request, User loginUser);

    /**
     * 将查询请求转换成QueryWrapper对象
     * @param userQueryRequest
     * @return
     */
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);
    /**
     * 用户对象转登录VO，脱敏
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * 判断是否为管理员
     * @param user
     * @return
     */
    boolean isAdmin(User user);

}
