package com.zjj.zjjpicturebackend.controller;


import com.zjj.zjjpicturebackend.common.BaseResponse;
import com.zjj.zjjpicturebackend.common.ResultUtils;
import com.zjj.zjjpicturebackend.manager.PictureAiManager;
import com.zjj.zjjpicturebackend.model.entity.PictureAiParseResult;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class AiController {

    @RestController
    @RequestMapping("/test/ai")
    public class PictureAiTestController {

        @Resource
        private PictureAiManager pictureAiManager;

        /**
         * 测试图片AI解析
         * @param imageUrl 图片公网可访问url
         * @return AI解析结果
         */
        @GetMapping("/parsePicture")
        public BaseResponse<PictureAiParseResult> parsePicture(@RequestParam String imageUrl) {
            PictureAiParseResult parseResult = pictureAiManager.parsePicture(imageUrl);
            return ResultUtils.success(parseResult);
        }
    }
}
