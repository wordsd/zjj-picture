package com.zjj.zjjpicturebackend.model.dto.user;

import lombok.Data;
import java.io.Serializable;

@Data
public class UserMyInfoUpdateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 头像
     */
    private String userAvatar;

    /**
     * 简介
     */
    private String userProfile;
}
