package com.zjj.zjjpicturebackend.service.impl;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zjj.zjjpicturebackend.auth.StpKit;
import com.zjj.zjjpicturebackend.constant.UserConstant;
import com.zjj.zjjpicturebackend.exception.BusinessException;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.model.dto.user.*;
import com.zjj.zjjpicturebackend.model.entity.User;
import com.zjj.zjjpicturebackend.model.enums.UserRoleEnum;
import com.zjj.zjjpicturebackend.model.vo.LoginUserVO;
import com.zjj.zjjpicturebackend.model.vo.UserVO;
import com.zjj.zjjpicturebackend.service.UserService;
import com.zjj.zjjpicturebackend.mapper.UserMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;


/**
 * @author Administrator
 * @description 针对表【user(用户)】的数据库操作Service实现
 * @createDate 2026-08-25 16:42:11
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    @Resource
    private UserMapper userMapper;

    /**
     * 用户注册
     *
     * @param userAccount   账号
     * @param userPassword  密码
     * @param checkPassword 确认密码
     * @param shareCode     邀请码
     * @return
     */
    @Override
    public long register(String userAccount, String userPassword, String checkPassword, String shareCode) {
        // 1.校验参数
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号密码不能为空");
        }
        //账号长度校验
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能小于4位");
        }
        //密码长度校验
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能小于8位");
        }

        // 2.校验两次密码是否一致
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入密码不一致");
        }

        // 3.检查账号是否重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        long count = this.count(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }

        //4.调用抽取的加密方法
        String encryptPassword = getEncryptPassword(userPassword);

        //5. 邀请码逻辑：根据传入shareCode查询邀请人
        Long inviteUserId = null;
        if (StringUtils.isNotBlank(shareCode)) {
            QueryWrapper<User> inviteWrapper = new QueryWrapper<>();
            inviteWrapper.eq("shareCode", shareCode);
            User inviteUser = this.getOne(inviteWrapper);
            if (inviteUser != null) {
                inviteUserId = inviteUser.getId();
            }
        }

        //6.构建新用户对象
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        user.setUserRole(UserRoleEnum.USER.getValue()); //新注册默认普通用户

        //循环生成唯一分享码，避免重复
        String myShareCode;
        do {
            myShareCode = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        } while (this.count(new QueryWrapper<User>().eq("shareCode", myShareCode)) > 0);
        user.setShareCode(myShareCode);
        user.setInviteUser(inviteUserId);

        //会员字段默认null
        user.setVipExpireTime(null);
        user.setVipCode(null);
        user.setVipNumber(null);
        //7.添加到数据库
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
        }
        return user.getId();
    }

    /**
     * 用户登录
     *
     * @param userAccount  账号
     * @param userPassword 密码
     * @param request
     * @return
     */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1.校验参数
        // 判空
        if (userAccount == null || userPassword == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号密码不能为空");
        }
        //账号长度校验
        if (userAccount.length() < UserConstant.USER_ACCOUNT_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号长度不能小于4位");
        }
        //密码长度校验
        if (userPassword.length() < UserConstant.USER_PASSWORD_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能小于8位");
        }
        //账号不能包含特殊字符
        if (ReUtil.contains(UserConstant.INVALID_ACCOUNT_CHAR, userAccount)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号含有非法字符");
        }

        // 2.密码加密
        String encryptPassword = getEncryptPassword(userPassword);

        // 3.查询账号是否存在
        List<User> userList = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUserAccount, userAccount)
                        .eq(User::getUserPassword, encryptPassword)
        );

        if (userList.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码错误");
        }

// 如果查出了多个同账号同密码的用户，说明数据脏了，取第一个并记录警告，或者直接报错
        if (userList.size() > 1) {
            // 建议在这里打印日志，提醒管理员清理脏数据
            log.warn("登录时发现重复账号数据，userAccount = {}", userAccount);
            // 严格模式下可以直接抛出异常：throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统内部错误，请联系管理员");
        }

        User queryUser = userList.get(0);

        //判断用户是否被封禁（可选，你的User表有userStatus字段就打开）
    /*if(UserConstant.BAN_ROLE.equals(queryUser.getUserRole())){
        throw new BusinessException(ErrorCode.FORBIDDEN,"账号已被封禁");
    }*/

        // 4.记录用户的登录态（session）
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, queryUser);
        //记录用户的登录态到Sa-token,便于空间鉴权是使用，注意保证该用户信息与SpringSession中的信息过期时间一致
        StpKit.SPACE.login(queryUser.getId());
        StpKit.SPACE.getSession().set(UserConstant.USER_LOGIN_STATE, queryUser);
        //组装返回VO，脱敏：不要返回密码
        return getLoginUserVO(queryUser);
    }

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        //1. 从session获取登录态
        Object loginObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User currentUser = (User) loginObj;
        ;

        // 判断是否登录
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "请先登录");
        }
        // 查询当前登录用户（从数据库拿最新数据，不直接返回session里旧对象）
        Long userId = currentUser.getId();
        currentUser = userMapper.selectById(userId);

        // 校验：数据库查不到该用户（用户已被删除）
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "用户不存在，请重新登录");
        }
        return currentUser;
    }

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 1.先判断是否已经登录
        Object loginObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        // 如果没有登录，抛异常
        if (loginObj == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "未登录");
        }
        // 2.移除当前登录用户的登录态
        request.getSession().removeAttribute(UserConstant.USER_LOGIN_STATE);
        return true;
    }


    /**
     * 创建用户
     * @param userAddRequest
     * @return
     */
    @Override
    public long addUser(UserAddRequest userAddRequest) {
        String userAccount = userAddRequest.getUserAccount();
        String userPassword = userAddRequest.getUserPassword();
        if(StringUtils.isAnyBlank(userAccount,userPassword)){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"账号密码不能为空");
        }
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUserAccount,userAccount);
        if(this.count(wrapper) >0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"账号已存在");
        }
        User user = new User();
        BeanUtils.copyProperties(userAddRequest,user);
        //密码加密
        String encrypt =getEncryptPassword(userPassword);
        user.setUserPassword(encrypt);
        boolean save = this.save(user);
        if(!save){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"新增用户失败");
        }
        return user.getId();
    }

    /**
     * 删除用户
     * @param id
     * @return
     */
    @Override
    public boolean deleteUser(long id) {
        if(id <=0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"id非法");
        }
        return this.removeById(id);
    }

    @Override
    public boolean updateUser(UserUpdateRequest userUpdateRequest) {
        Long id = userUpdateRequest.getId();
        if(id == null || id <=0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"id不能为空");
        }
        User user = new User();
        BeanUtils.copyProperties(userUpdateRequest,user);
        return this.updateById(user);
    }










    /**
     * 根据id获取用户
     *
     * @param user
     * @return
     */
    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    /**
     * 分页获取用户封装列表
     * @param userList
     * @return
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (userList == null) {
            return null;
        }
        return userList.stream().map(user -> getUserVO(user)).collect(Collectors.toList());
    }

    /**
     * 会员兑换功能
     * @param vipExchangeRequest
     * @param loginUser 当前登录用户
     * @return
     */
    @Override
    public boolean exchangeVip(VipExchangeRequest vipExchangeRequest, User loginUser) {
        // 1.参数校验
        if (vipExchangeRequest == null || StringUtils.isBlank(vipExchangeRequest.getCode())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "兑换码不能为空");
        }
        String code = vipExchangeRequest.getCode();
        Long userId = loginUser.getId();

        // =========这里写你的兑换业务逻辑=========
        // 示例逻辑，你可按需改造：
        // 1）校验兑换码是否存在、是否过期、是否已经被使用
        // 2）标记兑换码已被占用
        // 3）更新用户会员过期时间 user.setVipExpireTime(xxx);

        // 模拟示例，仅演示流程
        if (!"VIP888".equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"兑换码无效");
        }

        User user = new User();
        user.setId(userId);
        // 假设：给用户增加7天会员
        // user.setVipExpireTime(LocalDateTime.now().plusDays(7));
        boolean update = this.updateById(user);
        if(!update){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"会员兑换失败");
        }
        return true;
    }

    /**
     * 当前登录用户修改个人信息
     * @param request
     * @param loginUser
     * @return
     */
    @Override
    public boolean updateMyInfo(UserMyInfoUpdateRequest request, User loginUser) {
        User user = new User();
        user.setId(loginUser.getId());
        user.setUserName(request.getUserName());
        user.setUserAvatar(request.getUserAvatar());
        user.setUserProfile(request.getUserProfile());
        return this.updateById(user);
    }


    /**
     * 脱敏
     *
     * @param user
     * @return
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    /**
     * 将查询请求转换成QueryWrapper对象
     * @param userQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjUtil.isNotNull(id), "id", id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }


    /**
     * 密码加密
     *
     * @param rawPassword 原始明文密码
     * @return 加密后的密码
     */
    private String getEncryptPassword(String rawPassword) {
        // 原有MD5加盐逻辑，SALT是你的盐常量
        String salt = "你的盐值SALT";
        return DigestUtils.md5DigestAsHex((salt + rawPassword).getBytes());

        // 如果后续换成BCrypt，直接替换这一行即可：
        // return new BCryptPasswordEncoder().encode(rawPassword);
    }
    /**
     * 判断是否为管理员
     * @param user
     * @return
     */
    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }


}




