package com.zjj.zjjpicturebackend.manager.upload;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.NumberUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import com.zjj.zjjpicturebackend.config.CosClientConfig;
import com.zjj.zjjpicturebackend.manager.CosManager;
import com.zjj.zjjpicturebackend.model.dto.file.UploadPictureResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

@Slf4j
public abstract class  PictureUploadTemplate {
    @Resource
    private CosManager cosManager;

    @Resource
    private CosClientConfig cosClientConfig;

    // ----------------常量 允许上传的图片后缀----------------
    private static final List<String> ALLOW_SUFFIX = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
    // 最大图片大小 10MB
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024L;

    /**
     * 上传图片主入口
     * @param inputSource 文件/url
     * @param uploadPathPrefix 上传前缀，比如 picture/user
     * @return FileUploadResult
     */
    public UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix) {
        // 1.图片校验
        validPicture(inputSource);
        // 2.图片上传的地址
        String uuid= IdUtil.randomUUID();
        //获取原始文件名
        String originalFileName = getOriginalFilename(inputSource);
        //拼接上传文件名称
        String uploadFileName = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, FileUtil.getSuffix(originalFileName));
        //拼接上传路径 prefix/2026/08/uuid_原始文件名*
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFileName);
        // 3.上传cos
        File file=null;
        try {
            //上传图片
            //创建临时文件
            file = File.createTempFile("temp", null);
            //处理文件来源
            processFile(inputSource,file);
            //上传图片
            PutObjectResult putObjectResult = cosManager.putObject(uploadPath, file);
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            ProcessResults processResults = putObjectResult.getCiUploadResult().getProcessResults();
            List<CIObject> objectList = processResults.getObjectList();
            if(CollUtil.isNotEmpty(objectList)){
                CIObject compressedCiObject = objectList.get(0);
               CIObject thumbnailCiObject=compressedCiObject;
                if(objectList.size()>1){
                    thumbnailCiObject=objectList.get(1);
                }
                //封装压缩图返回结果
                return buildResult(originalFileName,compressedCiObject,thumbnailCiObject,imageInfo);

            }
            //返回封装结果
            return buildResult(imageInfo,originalFileName,file,uploadPath);
        } catch (Exception e) {
            log.error("文件上传失败",e);
            throw new RuntimeException("文件上传异常");
        }finally {
            this.deleteTempFile(file);
        }
    }



    private UploadPictureResult buildResult(String originFilename, CIObject compressedCiObject, CIObject thumbnailCiObject,ImageInfo imageInfo) {
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        int picWidth = compressedCiObject.getWidth();
        int picHeight = compressedCiObject.getHeight();
        double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicColor(imageInfo.getAve());
        uploadPictureResult.setPicFormat(compressedCiObject.getFormat());
        uploadPictureResult.setPicSize(compressedCiObject.getSize().longValue());
        // 设置图片为压缩后的地址
        uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + compressedCiObject.getKey());
        uploadPictureResult.setThumbnailUrl(cosClientConfig.getHost() + "/" + thumbnailCiObject.getKey());
        return uploadPictureResult;
    }



    protected abstract void processFile(Object inputSource, File file) throws Exception;

    protected abstract String getOriginalFilename(Object inputSource);

    protected abstract void validPicture(Object inputSource);

    public UploadPictureResult buildResult(ImageInfo imageInfo,String originalFileName,File file,String uploadPath){
        UploadPictureResult uploadPictureResult = new UploadPictureResult();
        int picWidth = imageInfo.getWidth();
        int picHeight = imageInfo.getHeight();
        double picScale= NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
        uploadPictureResult.setPicHeight(picHeight);
        uploadPictureResult.setPicWidth(picWidth);
        uploadPictureResult.setPicScale(picScale);
        uploadPictureResult.setPicName(FileUtil.mainName(originalFileName));
        uploadPictureResult.setPicFormat(imageInfo.getFormat());
        uploadPictureResult.setPicSize(FileUtil.size(file));
        uploadPictureResult.setPicColor(imageInfo.getAve());
        uploadPictureResult.setUrl(cosClientConfig.getHost()+"/"+uploadPath);
        return uploadPictureResult;
    }
    /**
     * 删除临时文件
     */
    private void deleteTempFile(File file){
        if(file==null){
            return;
        }
        //删除临时文件
        boolean deleteResult = file.delete();
        if(!deleteResult){
            log.error("临时文件删除失败");
        }

    }
}
