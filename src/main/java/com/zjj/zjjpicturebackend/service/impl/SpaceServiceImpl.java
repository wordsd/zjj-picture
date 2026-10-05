package com.zjj.zjjpicturebackend.service.impl;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zjj.zjjpicturebackend.common.DeleteRequest;
import com.zjj.zjjpicturebackend.controller.PictureController;
import com.zjj.zjjpicturebackend.exception.BusinessException;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.exception.ThrowUtils;
import com.zjj.zjjpicturebackend.model.dto.space.*;
import com.zjj.zjjpicturebackend.model.entity.Picture;
import com.zjj.zjjpicturebackend.model.entity.User;
import com.zjj.zjjpicturebackend.model.enums.SpaceLevelEnum;
import com.zjj.zjjpicturebackend.model.vo.SpaceVO;
import com.zjj.zjjpicturebackend.service.PictureService;
import com.zjj.zjjpicturebackend.service.SpaceService;
import com.zjj.zjjpicturebackend.mapper.SpaceMapper;
import com.zjj.zjjpicturebackend.service.UserService;
import jakarta.annotation.Resource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author Administrator
 * @description 针对表【space(空间)】的数据库操作Service实现
 * @createDate 2026-09-16 14:38:54
 */
@Service
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space>
        implements SpaceService {
    @Resource
    private UserService userService;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    @Lazy
    private PictureService pictureService;
    @Lazy
    @Resource
    private PictureController pictureController;
    Map<Long, Object> lockMap = new ConcurrentHashMap<>();

    /**
     * 创建空间
     */
    @Override
    public long addSpace(SpaceAddRequest spaceAddRequest, User loginUser) {
        ThrowUtils.throwIf(spaceAddRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        // 实体转换
        Space space = new Space();
        BeanUtils.copyProperties(spaceAddRequest, space);
        //默认值填充
        if (StrUtil.isBlank(spaceAddRequest.getSpaceName())) {
            space.setSpaceName("默认空间");
        }
        if (spaceAddRequest.getSpaceLevel() == null) {
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }
        //填充数据
        fillSpaceBySpaceLevel(space);
        //数据校验
        this.validSpace(space, true);
        Long userId = loginUser.getId();
        space.setUserId(userId);
        //权限校验
        if (SpaceLevelEnum.COMMON.getValue() != space.getSpaceLevel() && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无法创建指定级别的空间");
        }
        //针对用户加锁
        // String lock = String.valueOf(userId).intern();
        //保证锁的及时释放，使用本地锁
        Object lock = lockMap.computeIfAbsent(userId, k -> new Object());
        synchronized (lock) {
            try {
                Long newSpaceId = transactionTemplate.execute(status -> {
                    boolean exists = this.lambdaQuery().eq(Space::getUserId, userId).exists();
                    ThrowUtils.throwIf(exists, ErrorCode.PARAMS_ERROR, "用户已存在空间");
                    //写入数据库
                    boolean result = this.save(space);
                    ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建空间失败");
                    return space.getId();
                });
                return Optional.ofNullable(newSpaceId).orElse(-1L);
            } finally {
                lockMap.remove(userId);
            }
        }
    }

    /**
     * 更新空间（管理员全字段可改）
     */
    @Override
    public void updateSpace(SpaceUpdateRequest spaceUpdateRequest, User loginUser) {
        ThrowUtils.throwIf(spaceUpdateRequest == null || spaceUpdateRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 查询旧数据
        Long id = spaceUpdateRequest.getId();
        Space oldSpace = this.getById(id);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");
        // 组装更新对象
        Space space = new Space();
        BeanUtils.copyProperties(spaceUpdateRequest, space);
        // 若修改了空间级别，重新按级别刷新上限
        if (space.getSpaceLevel() != null
                && !space.getSpaceLevel().equals(oldSpace.getSpaceLevel())) {
            fillSpaceBySpaceLevel(space);
        }
        boolean result = this.updateById(space);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新空间失败");
    }

    /**
     * 编辑空间（用户侧，仅允许改名称）
     */
    @Override
    public void editSpace(SpaceEditRequest spaceEditRequest, User loginUser) {
        ThrowUtils.throwIf(spaceEditRequest == null || spaceEditRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 查询旧数据
        Long id = spaceEditRequest.getId();
        Space oldSpace = this.getById(id);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

        // 权限校验
        checkSpaceAuth(loginUser, oldSpace);

        // 用户侧只能改空间名
        Space space = new Space();
        space.setId(id);
        space.setSpaceName(spaceEditRequest.getSpaceName());
        validSpace(space, false);

        boolean result = this.updateById(space);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "编辑空间失败");
    }

    /**
     * 删除空间
     */
    //todo 添加空间管理员校验逻辑
    @Override
    public void deleteSpace(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        Space oldSpace = this.getById(id);
        ThrowUtils.throwIf(oldSpace == null, ErrorCode.NOT_FOUND_ERROR, "空间不存在");

        // 仅本人或管理员可删除
        checkSpaceAuth(loginUser, oldSpace);
        //关联删除数据库和对象存储中的图片
        //1、查询空间中的图片
        List<Picture> pictureList = pictureService.list(new QueryWrapper<Picture>().eq("space_id", id));
        //2、获取图片id集合
        List<Long> pictureIdList = pictureList.stream().map(Picture::getId).collect(Collectors.toList());
        try {
            for (Long pictureId  : pictureIdList) {
                //3、删除图片
                pictureController.deletePicture(new DeleteRequest(pictureId), null);
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除图片失败");
        }
        //4、删除空间
        boolean result = this.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除空间失败");
    }

    /**
     * 获取封装空间信息（单条）
     */
    @Override
    public SpaceVO getSpaceVO(Space space) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);
        SpaceVO spaceVO = SpaceVO.objToVo(space);

        // 关联查询创建者信息
        Long userId = space.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            spaceVO.setUser(userService.getUserVO(user));
        }
        return spaceVO;
    }

    /**
     * 分页获取空间封装信息
     */
    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage) {
        // 1. 取出当页空间实体
        List<Space> spaceList = spacePage.getRecords();

        // 2. 新建 VO 分页，分页参数原样复用
        Page<SpaceVO> spaceVOPage = new Page<>(
                spacePage.getCurrent(),
                spacePage.getSize(),
                spacePage.getTotal()
        );

        // 3. 空直接返回
        if (spaceList == null || spaceList.isEmpty()) {
            return spaceVOPage;
        }

        // 4. 实体 -> VO
        List<SpaceVO> spaceVOList = spaceList.stream()
                .map(SpaceVO::objToVo)
                .collect(Collectors.toList());

        // 5. 收集所有 userId 去重
        Set<Long> userIdSet = spaceList.stream()
                .map(Space::getUserId)
                .collect(Collectors.toSet());

        // 6. 批量查询用户（一次性查询，避免 N+1）
        Map<Long, User> userIdUserMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        // 7. 填充脱敏用户
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = userIdUserMap.get(userId);
            spaceVO.setUser(userService.getUserVO(user));
        });

        // 8. 回填记录并返回
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }

    /**
     * 获取查询条件
     */
    @Override
    public QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if (spaceQueryRequest == null) {
            return queryWrapper;
        }

        // 从对象中取值
        Long id = spaceQueryRequest.getId();
        Long userId = spaceQueryRequest.getUserId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();

        // 拼接查询条件
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName), "spaceName", spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel), "spaceLevel", spaceLevel);

        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField),
                "ascend".equals(sortOrder), sortField);
        return queryWrapper;
    }

    /**
     * 空间校验
     */
    @Override
    public void validSpace(Space space, boolean add) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);

        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum levelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);

        // 新增场景下名称必须填写
        if (add) {
            ThrowUtils.throwIf(StrUtil.isBlank(spaceName), ErrorCode.PARAMS_ERROR, "空间名称不能为空");
            if (levelEnum == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不存在");
            }
        }
        //修改数据时，如果要该空间级别
        if (spaceLevel != null && levelEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不存在");
        }
        if (StrUtil.isNotBlank(spaceName) && spaceName.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称不能超过20个字符");
        }
    }

    /**
     * 根据空间级别填充上限参数
     */
    @Override
    public void fillSpaceBySpaceLevel(Space space) {
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(space.getSpaceLevel());
        if (spaceLevelEnum == null) {
            return;
        }
        // 如果前端没有手动指定上限，则使用级别默认值
        if (space.getMaxSize() == null) {
            space.setMaxSize(spaceLevelEnum.getMaxSize());
        }
        if (space.getMaxCount() == null) {
            space.setMaxCount(spaceLevelEnum.getMaxCount());
        }
    }

    /**
     * 校验用户对空间的操作权限（本人或管理员）
     */
    @Override
    public void checkSpaceAuth(User loginUser, Space space) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(space == null || space.getUserId() == null,
                ErrorCode.PARAMS_ERROR);
        // 仅本人或管理员可操作
        if (!space.getUserId().equals(loginUser.getId())
                && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

}




