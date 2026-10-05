package com.zjj.zjjpicturebackend.manager;


import com.alibaba.fastjson2.JSON;
import com.volcengine.ark.runtime.model.responses.common.ResponsesText;
import com.volcengine.ark.runtime.model.responses.constant.ResponsesConstants;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemImage;
import com.volcengine.ark.runtime.model.responses.content.InputContentItemText;
import com.volcengine.ark.runtime.model.responses.content.OutputContentItem;
import com.volcengine.ark.runtime.model.responses.content.OutputContentItemText;
import com.volcengine.ark.runtime.model.responses.item.BaseItem;
import com.volcengine.ark.runtime.model.responses.item.ItemEasyMessage;
import com.volcengine.ark.runtime.model.responses.item.ItemOutputMessage;
import com.volcengine.ark.runtime.model.responses.item.MessageContent;
import com.volcengine.ark.runtime.model.responses.request.CreateResponsesRequest;
import com.volcengine.ark.runtime.model.responses.request.ResponsesInput;
import com.volcengine.ark.runtime.model.responses.response.ResponseObject;
import com.volcengine.ark.runtime.service.ArkService;
import com.zjj.zjjpicturebackend.model.entity.PictureAiParseResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PictureAiManager {
    @Value("${ai.doubao.api-key}")
    private String apiKey;

    public PictureAiParseResult parsePicture(String imageUrl) {
        ArkService arkService = null;
        try {
            // 创建ArkService实例
            arkService = ArkService.builder().apiKey(apiKey).baseUrl("https://ark.cn-beijing.volces.com/api/v3").build();
            String prompt = """
                    你是图片解析助手，请分析这张图片，只输出严格JSON，不要多余解释、不要markdown标题。
                    字段：
                    introduction：简短图片简介，不超过80个字；
                    tagNameList：字符串数组，5‑8个图片标签；
                    categoryName：图片所属分类，例如风景、人物、动物、建筑。
                    """;
            CreateResponsesRequest request = CreateResponsesRequest.builder()
                    .model("doubao-seed-evolving")
                    .input(ResponsesInput.builder().addListItem(
                            ItemEasyMessage.builder()
                                    .role(ResponsesConstants.MESSAGE_ROLE_USER)
                                    .content(MessageContent.builder()
                                            .addListItem(InputContentItemImage.builder().imageUrl(imageUrl).build())
                                            .addListItem(InputContentItemText.builder().text(prompt).build())
                                            .build()
                                    ).build()).build()).build();


            ResponseObject resp = arkService.createResponse(request);
            System.out.println("===完整resp输出===" + resp);

            String content = null;
            List<BaseItem> outputList = resp.getOutput();
            System.out.println("outputList:" + outputList);
            if (outputList != null && !outputList.isEmpty()) {
                // 遍历全部输出，找到ItemOutputMessage，跳过ItemReasoning推理块
                for (BaseItem baseItem : outputList) {
                    System.out.println("baseItem真实类名：" + baseItem.getClass().getName());
                    if (baseItem instanceof ItemOutputMessage itemOutputMessage) {
                        var contentList = itemOutputMessage.getContent();
                        System.out.println("contentList:" + contentList);
                        if (contentList != null && !contentList.isEmpty()) {
                            // 拿到第一个，强转为文本子类
                            var outputContentItem = contentList.get(0);
                            if(outputContentItem instanceof OutputContentItemText textItem){
                                content = textItem.getText();
                            }
                            break; //拿到就退出循环
                        }

                    }
                }
            }

            System.out.println("提取出来的content = " + content);
            if (content == null || content.isBlank()) {
                System.err.println("豆包模型返回内容为空");
                return null;
            }
            System.out.println("AI原始返回：" + content);

            // 清除markdown标记
            content = content.replace("```json", "")
                    .replace("```", "")
                    .trim();

            return JSON.parseObject(content, PictureAiParseResult.class);


        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (arkService != null) {
                arkService.shutdownExecutor();
            }
        }

    }
}
