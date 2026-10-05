package com.zjj.zjjpicturebackend.constant;

/**
 * 用户相关常量
 */
public interface UserConstant {

    /**
     * 用户登录态 key，存Session
     */
    String USER_LOGIN_STATE = "user_login";

    // -------- 用户角色常量 --------
    /**
     * 普通用户
     */
    String USER_ROLE = "user";

    /**
     * 管理员
     */
    String ADMIN_ROLE = "admin";

    // -------- 账号限制常量 --------
    /**
     * 账号最小长度
     */
    int USER_ACCOUNT_MIN_LENGTH = 4;

    /**
     * 密码最小长度
     */
    int USER_PASSWORD_MIN_LENGTH = 8;

    /**
     * 账号非法字符
     */
    String INVALID_ACCOUNT_CHAR = "[`&*()+=|{}':;',\\\\[\\\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]";

}
