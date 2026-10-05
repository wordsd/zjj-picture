package com.zjj.zjjpicturebackend.controller;

import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.utils.IOUtils;
import com.zjj.zjjpicturebackend.annotation.AuthCheck;
import com.zjj.zjjpicturebackend.common.BaseResponse;
import com.zjj.zjjpicturebackend.common.ResultUtils;
import com.zjj.zjjpicturebackend.constant.UserConstant;
import com.zjj.zjjpicturebackend.exception.BusinessException;
import com.zjj.zjjpicturebackend.exception.ErrorCode;
import com.zjj.zjjpicturebackend.manager.CosManager;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@RestController
@RequestMapping("/file")
@Slf4j
public class FileController {
    @Resource
    private CosManager cosManager;
    /**
     * 测试文件上传
     * 该接口用于处理文件上传请求，仅限管理员访问
     * @param multipartFile 上传的文件对象，通过@RequestPart注解获取
     * @return 返回BaseResponse<String>类型的结果，包含文件访问路径
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE) // 权限检查，要求用户角色为管理员
    @PostMapping("/test/upload") // POST请求映射，路径为/test/upload
    public BaseResponse<String> testUploadFile(@RequestPart("file") MultipartFile multipartFile) {
        // 文件目录   // 获取原始文件名
        String filename = multipartFile.getOriginalFilename(); // 构建文件存储路径
        String filepath = String.format("/test/%s", filename);
        File file = null;
        try {
            // 上传文件   // 创建临时文件
            file = File.createTempFile(filepath, null); // 将上传的文件内容写入临时文件
            multipartFile.transferTo(file); // 将文件上传至COS对象存储
            cosManager.putObject(filepath, file);
            // 返回可访问地址   // 返回成功响应，包含文件路径
            return ResultUtils.success(filepath);
        } catch (Exception e) { // 记录错误日志
            log.error("file upload error, filepath = " + filepath, e); // 抛出业务异常
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            if (file != null) {
                // 删除临时文件   // 尝试删除临时文件
                boolean delete = file.delete();
                if (!delete) { // 记录删除失败日志
                    log.error("file delete error, filepath = {}", filepath);
                }
            }
        }
    }


    /**
     * 测试文件下载
     *
     * @param filepath 文件路径
     * @param response 响应对象
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @GetMapping("/test/download/")
    public void testDownloadFile(String filepath, HttpServletResponse response) throws IOException {
        COSObjectInputStream cosObjectInput = null;
        try {
            COSObject cosObject = cosManager.getObject(filepath);
            cosObjectInput = cosObject.getObjectContent();
            // 处理下载到的流
            byte[] bytes = IOUtils.toByteArray(cosObjectInput);
            // 设置响应头
            response.setContentType("application/octet-stream;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=" + filepath);
            // 写入响应
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        } catch (Exception e) {
            log.error("file download error, filepath = " + filepath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "下载失败");
        } finally {
            if (cosObjectInput != null) {
                cosObjectInput.close();
            }
        }
    }


}
