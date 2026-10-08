package com.zjj.zjjpicturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zjj.zjjpicturebackend.aliyunAi.AliYunAiApi;
import com.zjj.zjjpicturebackend.aliyunAi.model.CreateImageGenerationTaskRequest;
import com.zjj.zjjpicturebackend.aliyunAi.model.CreateImageGenerationTaskResponse;
import com.zjj.zjjpicturebackend.exception.BusinessException;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.exception.ThrowUtils;
import com.zjj.zjjpicturebackend.manager.CosManager;
import com.zjj.zjjpicturebackend.manager.FileManager;
import com.zjj.zjjpicturebackend.manager.upload.FilePictureUpload;
import com.zjj.zjjpicturebackend.manager.upload.PictureUploadTemplate;
import com.zjj.zjjpicturebackend.manager.upload.UrlPictureUpload;
import com.zjj.zjjpicturebackend.model.dto.file.UploadPictureResult;
import com.zjj.zjjpicturebackend.model.dto.picture.*;
import com.zjj.zjjpicturebackend.model.dto.space.Space;
import com.zjj.zjjpicturebackend.model.entity.Picture;
import com.zjj.zjjpicturebackend.model.entity.User;
import com.zjj.zjjpicturebackend.model.enums.PictureReviewStatusEnum;
import com.zjj.zjjpicturebackend.model.vo.PictureVO;
import com.zjj.zjjpicturebackend.model.vo.UserVO;
import com.zjj.zjjpicturebackend.service.PictureService;
import com.zjj.zjjpicturebackend.mapper.PictureMapper;
import com.zjj.zjjpicturebackend.service.SpaceService;
import com.zjj.zjjpicturebackend.service.UserService;
import com.zjj.zjjpicturebackend.utils.ColorSimilarUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Administrator
 * @description 针对表【picture(图片)】的数据库操作Service实现
 * @createDate 2026-08-28 10:09:27
 */
@Slf4j
@Service
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture>
        implements PictureService {

    @Resource
    private FileManager fileManager;

    @Resource
    private UserService userService;
    @Autowired
    private FilePictureUpload filePictureUpload;
    @Autowired
    private UrlPictureUpload urlPictureUpload;
    @Autowired
    private CosManager cosManager;
    @Autowired
    private SpaceService spaceService;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private AliYunAiApi aliYunAiApi;

    /**
     * 上传图片
     *
     * @param pictureUpLoadRequest
     * @param loginUser
     * @return
     */
    @Override
    public PictureVO uploadPicture(Object inputSource, PictureUpLoadRequest pictureUpLoadRequest, User loginUser) {
        if (inputSource == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片为空");
        }
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        Long spaceId = pictureUpLoadRequest.getSpaceId();
        if (spaceId != null) {
            //判断空间是否存在
            Space space = spaceService.getById(spaceId);
            ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
            //必须是空间的管理员才能操作
            if (!loginUser.getId().equals(space.getUserId())) {
                ThrowUtils.throwIf(!userService.isAdmin(loginUser), ErrorCode.FORBIDDEN_ERROR, "无权限操作");
            }
            if (space.getTotalCount() >= space.getMaxCount()) {
                throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "空间条数不够");
            }
            if (space.getTotalSize() >= space.getMaxSize()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "空间大小不够");
            }
        }
        //判断室更新还是新增图片
        Long pictureId = null;
        if (pictureUpLoadRequest != null) {
            pictureId = pictureUpLoadRequest.getId();

        }

        //如果更行图片判断图片是否存在
        if (pictureId != null) {
            Picture oldPicture = this.getById(pictureId);
            boolean exists = this.lambdaQuery().eq(Picture::getId, pictureId).exists();
            ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            //仅本人或者管理员可以编辑图片
            if (!oldPicture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new RuntimeException("无权限编辑图片");
            }
            //校验空间是否一致
            //没传sapceId则默认为原空间
            if (spaceId == null) {
                if (oldPicture.getSpaceId() != null) {
                    spaceId = oldPicture.getSpaceId();
                }
            } else {
                ThrowUtils.throwIf(!oldPicture.getSpaceId().equals(spaceId), ErrorCode.FORBIDDEN_ERROR, "无权限编辑图片");
            }
        }
        //上传图片，根据用户id划分目录
        String uploadPathPrefix;
        if (spaceId == null) {
            uploadPathPrefix = String.format("public/%s", loginUser.getId());
        } else {
            uploadPathPrefix = String.format("space/%s", spaceId);
        }

        //跟据文件类型区分上传方式
        PictureUploadTemplate pictureUploadTemplate = filePictureUpload;
        if (inputSource instanceof String) {
            pictureUploadTemplate = urlPictureUpload;
        }
        UploadPictureResult uploadPictureResult = pictureUploadTemplate.uploadPicture(inputSource, uploadPathPrefix);
        //构造要入库的图片信息
        Picture picture = new Picture();
        picture.setUrl(uploadPictureResult.getUrl());
        picture.setThumbnailUrl(uploadPictureResult.getThumbnailUrl());
        picture.setName(uploadPictureResult.getPicName());
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        picture.setUserId(loginUser.getId());
        picture.setPicColor(uploadPictureResult.getPicColor());
        String picName = uploadPictureResult.getPicName();
        picture.setSpaceId(spaceId);
        if (pictureUpLoadRequest != null && StrUtil.isNotBlank(pictureUpLoadRequest.getPicName())) {
            picName = pictureUpLoadRequest.getPicName();
        }
        picture.setName(picName);
        //补充审核参数
        fillReviewParams(picture, loginUser);
        //如果pictureId不为空则更新图片信息
        if (pictureId != null) {
            picture.setId(pictureId);
            picture.setEditTime(new Date());
        }
        Long finalSpaceId = spaceId;
        transactionTemplate.execute(status -> {
            boolean result = this.saveOrUpdate(picture);
            ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片上传失败");
            if (finalSpaceId != null) {
                boolean update = spaceService.lambdaUpdate()
                        .eq(Space::getId, finalSpaceId)
                        .setSql("totalSize=totalSize+" + picture.getPicSize())
                        .setSql("totalCount=totalCount+1")
                        .update();
                ThrowUtils.throwIf(!update, ErrorCode.OPERATION_ERROR, "图片上传失败");
            }
            return picture;
        });


        return PictureVO.objToVo(picture);
    }

    /**
     * 删除图片
     *
     * @param oldPicture
     */
    @Async
    @Override
    public void clearPicture(Picture oldPicture) {
        //判断图片是否存在
        String pictureUrl = oldPicture.getUrl();
        long count = this.lambdaQuery().eq(Picture::getUrl, pictureUrl).count();
        if (count > 1) {
            return;
        }
        //删除图片
        cosManager.deleteObject(oldPicture.getUrl());
        //删除缩略图
        String thumbnailUrl = oldPicture.getThumbnailUrl();
        if (StrUtil.isNotBlank(thumbnailUrl)) {
            cosManager.deleteObject(thumbnailUrl);
        }

    }

    /**
     * 分页获取图片封装
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        // 拿到分页里面本次查询出来的图片实体记录
        List<Picture> pictureList = picturePage.getRecords();

        // 新建VO分页对象，复用原来分页的页码、页大小、总条数，分页参数不变，只替换内部记录
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());

        // Hutool工具类：如果图片列表为空，直接返回空分页，不用往下执行
        if (CollUtil.isEmpty(pictureList)) {
            return pictureVOPage;
        }

        // 1. List<Picture>实体集合 → List<PictureVO>视图集合，调用你写好的objToVo转换方法
        List<PictureVO> pictureVOList = pictureList.stream()
                .map(PictureVO::objToVo)
                .collect(Collectors.toList());

        // 2. 批量拿到所有图片的userId，去重，放入Set集合（避免重复查询同一个用户）
        Set<Long> userIdSet = pictureList.stream()
                .map(Picture::getUserId)
                .collect(Collectors.toSet());

        // 3. 根据一批userId批量查询用户，数据库只查1次！！！
        // groupingBy：key=用户id，value=该id对应的User对象集合
//        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
//                .collect(Collectors.groupingBy(User::getId));
        //优化
        Map<Long, User> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.toMap(User::getId, user -> user));


        // 4.遍历每一个PictureVO，填充上传者的脱敏用户VO
        pictureVOList.forEach(pictureVO -> {
            Long userId = pictureVO.getUserId();
            User user = null;
            // 如果map中存在这个userId，取出对应的用户对象
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId);
            }
            // userService.getUserVO(user)：User实体转UserVO，脱敏（剔除密码等敏感字段）赋值给图片VO
            pictureVO.setUser(userService.getUserVO(user));
        });

        // 把封装好的VO列表设置到分页对象
        pictureVOPage.setRecords(pictureVOList);
        // 返回封装完成的分页VO，直接返回给前端
        return pictureVOPage;
    }

    /**
     * 批量抓取和创建图片
     *
     * @param pictureUploadByBatchRequest
     * @param loginUser
     * @return
     */
    @Override
    public Integer uploadPictureByBatch(PictureUploadByBatchRequest pictureUploadByBatchRequest, User loginUser) {
        String searchText = pictureUploadByBatchRequest.getSearchText();
        String namePrefix = pictureUploadByBatchRequest.getNamePrefix();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }
        //格式化数量
        int count = pictureUploadByBatchRequest.getCount();
        ThrowUtils.throwIf(count > 30, ErrorCode.PARAMS_ERROR, "最多只能抓取30张图片");
        //要抓取的地址
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1", searchText);
        Document document;
        try {
            document = Jsoup.connect(fetchUrl).get();
        } catch (IOException e) {
            log.error("抓取图片失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "抓取图片失败");
        }
        Element div = document.getElementsByClass("dgControl").first();
        if (ObjUtil.isNull(div)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "抓取图片失败");
        }
        Elements imgElementList = div.select("img.mimg");
        int uploadCount = 0;
        for (Element imgElement : imgElementList) {
            String fileUrl = imgElement.attr("src");
            if (StrUtil.isBlank(fileUrl)) {
                log.info("当前链接为空，已跳过：{}", fileUrl);
                continue;
            }
            //处理图片地址,防止出现转义的问题
            int questionMarkIndex = fileUrl.indexOf("?");
            if (questionMarkIndex > -1) {
                fileUrl = fileUrl.substring(0, questionMarkIndex);
            }

            //上传图片
            PictureUpLoadRequest pictureUpLoadRequest = new PictureUpLoadRequest();
            if (StrUtil.isNotBlank(namePrefix)) {
                pictureUpLoadRequest.setPicName(namePrefix + (uploadCount + 1));
            }
            if (uploadCount >= count) {
                break;
            }
            try {
                PictureVO pictureVO = this.uploadPicture(fileUrl, pictureUpLoadRequest, loginUser);
                log.info("上传图片成功：id={}", pictureVO.getId());
                uploadCount++;
            } catch (Exception e) {
                log.error("上传图片失败", e);
            }
            if (uploadCount >= count) {
                break;
            }

        }
        return uploadCount;

    }

    /**
     * 颜色搜索图片
     * @param spaceId
     * @param picColor
     * @param loginUser
     * @return
     */
    @Override
    public List<PictureVO> searchPictureByColor(Long spaceId, String picColor, User loginUser) {
        //1、检验参数
        ThrowUtils.throwIf(spaceId == null || StrUtil.isBlank(picColor), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        //2、校验空间权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        ThrowUtils.throwIf(!space.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "没有权限");
        //3、查询该空间下的所有图片
        List<Picture> pictureList = this.lambdaQuery().eq(Picture::getSpaceId, spaceId).isNotNull(Picture::getPicColor).list();
        //没有图片直接返回空列表
        if (CollUtil.isEmpty(pictureList)) {
            return Collections.emptyList();
        }
        //将目标颜色转换为Color对象
        Color targetColor = Color.decode(picColor);
        //4、根据相似度排序
        List<Picture> sortedPictures = pictureList.stream().sorted(Comparator.comparingDouble(picture -> {
                    //提取图片主色调
                    String hexColor = picture.getPicColor();
                    //没有主色调放到最后
                    if (StrUtil.isBlank(hexColor)) {
                        return Double.MAX_VALUE;
                    }
                    Color pictureColor = Color.decode(hexColor);
                    //越大越相似
                    return -ColorSimilarUtils.calculateSimilarity(targetColor, pictureColor);
                }))
                .limit(12)
                .collect(Collectors.toList());
        //5、封装返回值
        return sortedPictures.stream().map(PictureVO::objToVo).collect(Collectors.toList());
    }

    /**
     * 批量编辑图片
     * @param pictureEditByBatchRequest
     * @param loginUser
     */
    @Override
    public void editPictureBatch(PictureEditByBatchRequest pictureEditByBatchRequest, User loginUser) {
        List<Long> pictureIdList = pictureEditByBatchRequest.getPictureIdList();
        Long spaceId = pictureEditByBatchRequest.getSpaceId();
        String category = pictureEditByBatchRequest.getCategory();
        List<String> tags = pictureEditByBatchRequest.getTags();
        String nameRule = pictureEditByBatchRequest.getNameRule();
        //校验参数
        ThrowUtils.throwIf(spaceId==null||CollUtil.isEmpty(pictureIdList), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        //校验空间权限
        Space space = spaceService.getById(spaceId);
        ThrowUtils.throwIf(space == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        ThrowUtils.throwIf(!space.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR, "没有权限");
        //查询指定图片
        List<Picture> pictureList = this.lambdaQuery().select(Picture::getId, Picture::getSpaceId)
                .eq(Picture::getSpaceId, spaceId)
                .in(Picture::getId, pictureIdList)
                .list();
        if (pictureList.isEmpty()){
            return;
        }
        //更新分类和标签
        pictureList.forEach(picture -> {
            if(StrUtil.isNotBlank(category)){
                picture.setCategory(category);
            }
            if(CollUtil.isNotEmpty(tags)){
                picture.setTags(JSONUtil.toJsonStr(tags));
            }
        });
        fillPictureWithName(pictureList, nameRule);
        //批量更新
        boolean result = this.updateBatchById(pictureList);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "批量更新失败");
    }

    /**
     * 填充图片名称
     * @param pictureList
     * @param nameRule
     */
    private void fillPictureWithName(List<Picture> pictureList, String nameRule) {
        if (CollUtil.isEmpty(pictureList) || StrUtil.isBlank(nameRule)) {
            return;
        }
        long count=1;
        try{
            for(Picture picture : pictureList) {
                nameRule.replaceAll("\\{序号}",String.valueOf(count++));
                picture.setName(nameRule);
            }
        }catch (Exception e){
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR, "命名规则有误");
        }
    }

    /**
     * 图片审核
     *
     * @param pictureReviewRequest
     * @param loginUser
     */
    @Override
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        //校验参数
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();
        PictureReviewStatusEnum reviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        //管理员只能将状态设置为通过或者拒绝
        if (id == null || reviewStatusEnum == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)) {
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR);
        }

        //查看图片是否存在
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        //已是该状态
        if (oldPicture.getReviewStatus().equals(reviewStatus)) {
            ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR, "已是该状态");
        }

        //更新审核状态
        Picture updatePicture = new Picture();
        BeanUtils.copyProperties(pictureReviewRequest, updatePicture);
        updatePicture.setReviewTime(new Date());
        updatePicture.setReviewerId(loginUser.getId());
        boolean result = this.updateById(updatePicture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片审核失败");
    }


    /**
     * 获取查询条件
     *
     * @param pictureQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        List<String> tags = pictureQueryRequest.getTags();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        String reviewMessage = pictureQueryRequest.getReviewMessage();
        Long reviewerId = pictureQueryRequest.getReviewerId();
        Long spaceId = pictureQueryRequest.getSpaceId();
        boolean nullSpaceId = pictureQueryRequest.isNullSpaceId();
        Date startEditTime = pictureQueryRequest.getStartEditTime();
        Date endEditTime = pictureQueryRequest.getEndEditTime();


        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            queryWrapper.and(qw -> qw.like("name", searchText)
                    .or()
                    .like("introduction", searchText)
            );
        }
        queryWrapper.ge(ObjUtil.isNotEmpty(startEditTime), "editTime", startEditTime);
        queryWrapper.lt(ObjUtil.isNotEmpty(endEditTime), "editTime", endEditTime);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceId), "spaceId", spaceId);
        queryWrapper.isNull(nullSpaceId, "spaceId");
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    /**
     * 获取图片信息
     *
     * @param picture
     * @param request
     * @return
     */
    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        PictureVO pictureVO = PictureVO.objToVo(picture);
        //关联查询用户信息
        Long userId = picture.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);

        }
        return pictureVO;
    }

    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = picture.getId();
        String url = picture.getUrl();
        String introduction = picture.getIntroduction();
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 1024, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 800, ErrorCode.PARAMS_ERROR, "简介过长");
        }
    }

    /**
     * 填充审核参数
     *
     * @param picture
     * @param loginUser
     */
    @Override
    public void fillReviewParams(Picture picture, User loginUser) {
        if (userService.isAdmin(loginUser)) {
            //管理员自动过审
            picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
            picture.setReviewerId(loginUser.getId());
            picture.setReviewTime(new Date());
            picture.setReviewMessage("管理员自动过审");
        } else {
            //非管理员，创建或编辑都改为待审核
            picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        }
    }

    /**
     *创建 AI 生图任务（文生图 / 图生图）
     * @param createPictureAiImageRequest 生图请求
     * @param loginUser                   当前登录用户
     * @return
     */
    @Override
    public CreateImageGenerationTaskResponse createPictureAiImageTask(CreatePictureAiImageRequest createPictureAiImageRequest, User loginUser) {
        //参数校验
        ThrowUtils.throwIf(createPictureAiImageRequest == null, ErrorCode.PARAMS_ERROR, "生图参数为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        String prompt = createPictureAiImageRequest.getPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        // 组装百炼请求
        CreateImageGenerationTaskRequest createImageGenerationTaskRequest = new CreateImageGenerationTaskRequest();
        createImageGenerationTaskRequest.setModel(createPictureAiImageRequest.getModel());
        List<CreateImageGenerationTaskRequest.ContentItem> contentItemList;
        Long pictureId = createPictureAiImageRequest.getPictureId();
        if (pictureId == null) {
            // 文生图：content 里只放一个 text
            contentItemList = List.of(CreateImageGenerationTaskRequest.ContentItem.ofText(prompt));
        } else {
            // 图生图：先取参考图、校验权限，再拼 image + text
            Picture picture = this.getById(pictureId);
            ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "参考图片不存在");
            this.checkPictureAuth(loginUser, picture);
            contentItemList = List.of(
                    CreateImageGenerationTaskRequest.ContentItem.ofImage(picture.getUrl()),
                    CreateImageGenerationTaskRequest.ContentItem.ofText(prompt)
            );
        }
        CreateImageGenerationTaskRequest.Message message = new CreateImageGenerationTaskRequest.Message();
        message.setContent(contentItemList);
        CreateImageGenerationTaskRequest.Input input = new CreateImageGenerationTaskRequest.Input();
        // 只支持单轮对话，因此 messages 里只能有一个元素
        input.setMessages(List.of(message));
        createImageGenerationTaskRequest.setInput(input);
        // 生成参数直接透传前端传来的参数
        createImageGenerationTaskRequest.setParameters(createPictureAiImageRequest.getParameters());
        // 创建任务
        return aliYunAiApi.createImageGenerationTask(createImageGenerationTaskRequest);
    }

    @Override
    public void checkPictureAuth(User loginUser, Picture picture) {
        Long spaceId = picture.getSpaceId();
        if (spaceId == null) {
            //公共图库，仅本人或管理员可以操作
            if (!picture.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        } else {
            //私有图库，仅本人可以操作
            if (!picture.getUserId().equals(loginUser.getId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
    }

}




