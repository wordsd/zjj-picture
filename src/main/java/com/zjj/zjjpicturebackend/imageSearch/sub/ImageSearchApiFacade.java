package com.zjj.zjjpicturebackend.imageSearch.sub;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.zjj.zjjpicturebackend.exception.BusinessException;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.exception.ThrowUtils;
import com.zjj.zjjpicturebackend.model.ImageSearchResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 以图搜图门面类
 * <p>
 * 对上层屏蔽具体的搜图实现（当前用百度识图），只对外暴露"给一张图片、返回相似图片列表"的能力。
 * 以后要换成别的搜图服务，只需要改这个类，Controller 不用动。
 */
@Slf4j
public class ImageSearchApiFacade {

    private ImageSearchApiFacade() {
        // 工具类，禁止实例化
    }

    /**
     * 根据图片 URL 搜索相似图片
     *
     * @param imageUrl 图片的公网可访问地址（必须能被搜图服务抓取到）
     * @return 相似图片列表，没有结果时返回空列表
     */
    public static List<ImageSearchResult> searchImage(String imageUrl) {
        ThrowUtils.throwIf(StrUtil.isBlank(imageUrl), ErrorCode.PARAMS_ERROR, "图片地址不能为空");
        BaiduResponse response;
        try {
            response = BaiduImageSearch.search(imageUrl);
        } catch (Exception e) {
            log.error("以图搜图失败，imageUrl={}", imageUrl, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图失败，请稍后重试");
        }
        return convert(response);
    }

    /**
     * 根据图片字节搜索相似图片
     * <p>
     * 适用于图片地址不可公网访问（比如私有读的对象存储）的场景
     *
     * @param imageBytes 图片二进制数据
     * @return 相似图片列表，没有结果时返回空列表
     */
    public static List<ImageSearchResult> searchImage(byte[] imageBytes) {
        ThrowUtils.throwIf(imageBytes == null || imageBytes.length == 0,
                ErrorCode.PARAMS_ERROR, "图片内容不能为空");
        BaiduResponse response;
        try {
            response = BaiduImageSearch.search(imageBytes);
        } catch (Exception e) {
            log.error("以图搜图失败，图片大小={}", imageBytes.length, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图失败，请稍后重试");
        }
        return convert(response);
    }

    /**
     * 把搜图服务返回的结果转换成前端需要的结构
     *
     * @param response 百度识图返回结果
     * @return 前端使用的相似图片列表
     */
    private static List<ImageSearchResult> convert(BaiduResponse response) {
        if (response == null) {
            return Collections.emptyList();
        }
        // 结果页地址为空，说明"上传图片"这一步就失败了；
        // 这里抛异常而不是返回空列表，避免前端把"调用失败"误当成"没有相似图片"
        if (StrUtil.isBlank(response.getUrl())) {
            log.error("以图搜图未取到结果页地址，搜图服务可能已变更或触发风控");
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "以图搜图失败，请稍后重试");
        }
        List<ImageSearchResult> resultList = new ArrayList<>();
        // 按来源地址去重
        Set<String> fromUrlSet = new HashSet<>();
        // 完全匹配（同源图）优先，再追加相似图
        append(resultList, fromUrlSet, response.getExactMatches());
        append(resultList, fromUrlSet, response.getRaw());
        log.info("以图搜图完成，结果数量={}", resultList.size());
        return resultList;
    }

    /**
     * 把 BaiduItem 列表追加到结果列表中，过滤脏数据并去重
     */
    private static void append(List<ImageSearchResult> resultList, Set<String> fromUrlSet, List<BaiduItem> itemList) {
        if (CollUtil.isEmpty(itemList)) {
            return;
        }
        for (BaiduItem item : itemList) {
            if (item == null) {
                continue;
            }
            String thumbUrl = item.getThumbnail();
            String fromUrl = item.getUrl();
            // 缩略图和来源都没有，属于脏数据，直接跳过
            if (StrUtil.isBlank(thumbUrl) && StrUtil.isBlank(fromUrl)) {
                continue;
            }
            // 有来源地址时按来源去重
            if (StrUtil.isNotBlank(fromUrl) && !fromUrlSet.add(fromUrl)) {
                continue;
            }
            ImageSearchResult imageSearchResult = new ImageSearchResult();
            imageSearchResult.setThumbUrl(thumbUrl);
            imageSearchResult.setFromUrl(fromUrl);
            resultList.add(imageSearchResult);
        }
    }
}
