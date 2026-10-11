package com.zjj.zjjpicturebackend.websocket.model;

import com.zjj.zjjpicturebackend.model.vo.PictureVO;
import com.zjj.zjjpicturebackend.model.vo.UserVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PictureEditResponseMessage {

    /**
     * 消息类型，例如 "INFO", "ERROR", "ENTER_EDIT", "EXIT_EDIT", "EDIT_ACTION", "EDIT_FINISH"
     */
    private String type;

    /**
     * 信息
     */
    private String message;

    /**
     * 执行的编辑动作
     */
    private String editAction;

    /**
     * 用户信息
     */
    private UserVO user;

    /**
     * 编辑完成后的最新图片信息，仅在 type = EDIT_FINISH 时有值
     */
    private PictureVO picture;
}
