package com.zjj.zjjpicturebackend.model.dto.picture;

import lombok.Data;

import java.io.Serializable;

@Data
public class PictureUploadByBatchRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    /**
     * 名称前缀
     */
    private String namePrefix;

    /**
     * 搜索内容
     */
    private String searchText;
    /**
     * 抓取数量
     */
    private Integer count=10;
}
