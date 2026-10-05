package com.zjj.zjjpicturebackend.manager;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.unit.DataUnit;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.zjj.zjjpicturebackend.config.CosClientConfig;
import com.zjj.zjjpicturebackend.exception.BusinessException;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.exception.ThrowUtils;
import com.zjj.zjjpicturebackend.model.dto.file.UploadPictureResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
@Deprecated
@Service
@Slf4j
public class FileManager {

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
     * @param multipartFile 文件
     * @param uploadPathPrefix 上传前缀，比如 picture/user
     * @return FileUploadResult
     */
    public UploadPictureResult uploadPicture(MultipartFile multipartFile, String uploadPathPrefix) {
        // 1.图片校验
        validImage(multipartFile);
        // 2.图片上传的地址
        String uuid= IdUtil.randomUUID();
        //获取原始文件名
        String originalFileName = multipartFile.getOriginalFilename();
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
            multipartFile.transferTo(file);
            //上传图片
            PutObjectResult putObjectResult = cosManager.putObject(uploadPath, file);
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            //返回封装结果
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
            uploadPictureResult.setUrl(cosClientConfig.getHost()+"/"+uploadPath);
            return uploadPictureResult;

        } catch (Exception e) {
            log.error("文件上传失败",e);
            throw new RuntimeException("文件上传异常");
        }finally {
            this.deleteTempFile(file);
        }
    }

    /**
     * 通过Url上传图片
     * @param fileUrl
     * @param uploadPathPrefix
     * @return
     */
    public UploadPictureResult  uploadPictureByUrl(String fileUrl, String uploadPathPrefix) {
        //校验图片
        validPicture(fileUrl);
        //图片上传地址
        String uuid= RandomUtil.randomString(16);
        String originFilename= FileUtil.mainName(fileUrl);
        String uploadFilename=String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, FileUtil.getSuffix(originFilename));
        String uploadPath = String.format("/%s/%s", uploadPathPrefix, uploadFilename);
        File file=null;
        try {
            //创建临时文件
            file = File.createTempFile(uploadPath,null);
            HttpUtil.downloadFile(fileUrl,file);
            //上传图片
            PutObjectResult putObjectResult = cosManager.putObject(uploadPath, file);
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            //返回封装结果
            UploadPictureResult uploadPictureResult = new UploadPictureResult();
            int picWidth = imageInfo.getWidth();
            int picHeight = imageInfo.getHeight();
            double picScale= NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
            uploadPictureResult.setPicHeight(picHeight);
            uploadPictureResult.setPicWidth(picWidth);
            uploadPictureResult.setPicScale(picScale);
            uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
            uploadPictureResult.setPicFormat(imageInfo.getFormat());
            uploadPictureResult.setPicSize(FileUtil.size(file));
            uploadPictureResult.setUrl(cosClientConfig.getHost()+"/"+uploadPath);
            return uploadPictureResult;
        }catch (Exception e) {
            log.error("文件上传失败",e);
            throw new RuntimeException("文件上传异常");
        }finally {
            this.deleteTempFile(file);
        }

    }

    /**
     * 校验图片
     * @param fileUrl
     */
    public void validPicture(String fileUrl){

        //校验Url格式
        try{
            new URL(fileUrl);
        }catch (MalformedURLException e){
            throw  new BusinessException(ErrorCode.PARAMS_ERROR,"图片地址格式错误");
        }
        //校验URL协议
        ThrowUtils.throwIf(!fileUrl.startsWith("http://") && !fileUrl.startsWith("https://"), ErrorCode.PARAMS_ERROR,"仅支持http和https协议");

        //发送Head请求验证图片是否存在
        HttpResponse response=null;
        try {
            HttpUtil.createRequest(Method.GET, fileUrl).execute();
            //未正常返回，无需执行其他判断
            if (response.getStatus() != HttpStatus.HTTP_OK) {
                return ;
            }
            //校验文件类型
            String contentType = response.header("Content-Type");
            if(StrUtil.isNotBlank(contentType)){
                //允许的图片类型
               final  List<String> ALLOW_CONTENT_TYPES = Arrays.asList("image/jpeg", "image/png", "image/gif", "image/bmp", "image/webp");
               ThrowUtils.throwIf(!ALLOW_CONTENT_TYPES.contains(contentType.toLowerCase()),ErrorCode.PARAMS_ERROR,"文件类型错误");
            }
            //校验文件大小
            String contentLengthStr = response.header("Content-Length");
            if(StrUtil.isNotBlank(contentLengthStr)){
                try{
                    long contentLength = Long.parseLong(contentLengthStr);
                    final long   TWO_MB = 1024 * 1024 * 2L;
                    ThrowUtils.throwIf(contentLength > TWO_MB, ErrorCode.PARAMS_ERROR,"图片不能超过2MB");
                }catch (NumberFormatException e){
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,"文件大小错误");
                }
            }
        }finally {
            if(response!=null)
                response.close();
        }

    }

    /**
     * 图片校验：大小、后缀
     */
    public void validImage(MultipartFile multipartFile) {
        //校验大小
        long size = multipartFile.getSize();
        if(size <= 0){
            throw new RuntimeException("文件不能为空");
        }
        if(size > MAX_FILE_SIZE){
            throw new RuntimeException("图片不能超过10MB");
        }
        //校验后缀
        String originalFilename = multipartFile.getOriginalFilename();
        String suffix = FileUtil.getSuffix(originalFilename);
        if(StrUtil.isBlank(suffix)){
            throw new RuntimeException("无法获取文件后缀");
        }
        String lowerSuffix = suffix.toLowerCase();
        if(!ALLOW_SUFFIX.contains(lowerSuffix)){
            throw new RuntimeException("仅支持jpg,jpeg,png,gif,bmp,webp图片");
        }
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