package com.zjj.zjjpicturebackend.model.entity;

import lombok.Data;

import java.util.List;

@Data
public class PictureAiParseResult {
    /**
     * 图片简介
     */
    private String introduction;
    /**
     * 标签名称集合
     */
    private List<String> tagNameList;
    /**
     * 分类名称
     */
    private String categoryName;
}
