package com.zjj.zjjpicturebackend.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 会员兑换请求
 */
@Data
public class VipExchangeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 兑换码
     */
    private String code;
}