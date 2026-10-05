package com.zjj.zjjpicturebackend.model.dto.picture;


import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;


/**
 * 图片
 * @TableName picture
 */

@Data
public class PictureUpLoadRequest implements Serializable {

        /**
         * 图片名称
         */
        private String picName;

        /**
         * 图片 id（用于修改）
         */
        private Long id;
        /**
         * 空间 id
         */
        private Long spaceId;

        /**
         * 图片地址
         */
        private String fileUrl;
        /**
         * 图片名称（上传时填写）
         */
        private String name;

        private static final long serialVersionUID = 1L;


}