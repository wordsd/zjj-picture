package com.zjj.zjjpicturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zjj.zjjpicturebackend.annotation.AuthCheck;
import com.zjj.zjjpicturebackend.common.BaseResponse;
import com.zjj.zjjpicturebackend.common.ResultUtils;
import com.zjj.zjjpicturebackend.constant.UserConstant;
import com.zjj.zjjpicturebackend.exception.BusinessException;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.exception.ThrowUtils;
import com.zjj.zjjpicturebackend.model.dto.user.*;
import com.zjj.zjjpicturebackend.model.entity.User;
import com.zjj.zjjpicturebackend.model.vo.LoginUserVO;
import com.zjj.zjjpicturebackend.model.vo.UserVO;
import com.zjj.zjjpicturebackend.service.UserService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/user")
public class UserController {
    @Resource
    private UserService userService;


    /**
     * 用户注册
     * @param userRegisterRequest
     * @return
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest){
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        String shareCode = userRegisterRequest.getShareCode();
        long id = userService.register(userAccount,userPassword,checkPassword,shareCode);
        return ResultUtils.success(id);
    }

    /**
     * 用户登录
     * @param userLoginRequest
     * @param request
     * @return
     */
    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request){
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        LoginUserVO loginUserVO = userService.userLogin(userAccount, userPassword, request);
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户信息
     * @param request
     * @return
     */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request){
        User loginUser = userService.getLoginUser(request);
        //转为脱敏VO返回前端
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }
    /**
     * 用户注销
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request){
        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }


    /**
     * 创建用户
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest) {
        long userId = userService.addUser(userAddRequest);
        return ResultUtils.success(userId);
    }
    /**
     * 删除用户
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestParam long id) {
        boolean result = userService.deleteUser(id);
        return ResultUtils.success(result);
    }
    /**
     * 更新用户
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        boolean result = userService.updateUser(userUpdateRequest);
        return ResultUtils.success(result);
    }
    /**
     * 根据id获取用户原始实体（数据库对象，仅内部使用，不返回前端）
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(user);
    }

    /**
     * 根据id获取包装UserVO（脱敏返回前端）
     */
    @GetMapping("/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<UserVO> getUserVOById(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        UserVO userVO = userService.getUserVO(user);
        return ResultUtils.success(userVO);
    }

    /**
     * 分页获取用户封装列表（仅管理员）
     *
     * @param userQueryRequest 查询请求参数
     */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = userQueryRequest.getCurrent();
        long pageSize = userQueryRequest.getPageSize();
        Page<User> userPage = userService.page(new Page<>(current, pageSize),
                userService.getQueryWrapper(userQueryRequest));
        Page<UserVO> userVOPage = new Page<>(current, pageSize, userPage.getTotal());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }
    /**
     * POST /api/user/exchange/vip
     * 会员兑换（普通登录用户，不需要管理员）
     */
    @PostMapping("/exchange/vip")
    @AuthCheck
    public BaseResponse<Boolean> exchangeVip(@RequestBody VipExchangeRequest vipExchangeRequest, HttpServletRequest request){
        User loginUser = userService.getLoginUser(request);
        boolean result = userService.exchangeVip(vipExchangeRequest, loginUser);
        return ResultUtils.success(result);
    }
    /**
     * POST /api/user/my/update
     * 用户修改自己个人信息，登录即可
     */
    @PostMapping("/my/update")
    @AuthCheck
    public BaseResponse<Boolean> updateMyInfo(@RequestBody UserMyInfoUpdateRequest userMyInfoUpdateRequest, HttpServletRequest request){
        User loginUser = userService.getLoginUser(request);
        boolean ok = userService.updateMyInfo(userMyInfoUpdateRequest, loginUser);
        return ResultUtils.success(ok);
    }

}
