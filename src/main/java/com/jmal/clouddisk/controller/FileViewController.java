package com.jmal.clouddisk.controller;

import com.jmal.clouddisk.exception.CommonException;
import com.jmal.clouddisk.exception.ExceptionType;
import com.jmal.clouddisk.service.IFileService;
import com.jmal.clouddisk.service.IShareService;
import com.jmal.clouddisk.service.impl.ShareServiceImpl;
import com.jmal.clouddisk.util.ResultUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * @author jmal
 * @Description TODO
 * @Date 2020/10/19 3:55 下午
 */
@Api(tags = "文件资源管理")
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
public class FileViewController {

    @Autowired
    private IFileService fileService;

    @Autowired
    IShareService shareService;

    @ApiOperation("分享：预览文件")
    @GetMapping("/public/preview/{fileId}")
    public String publicPreview(@PathVariable String fileId) {
        return fileService.viewFile(fileId, "preview");
    }

    @ApiOperation("预览文档里的图片")
    @GetMapping("/public/view")
    public String imageRelativePath(@RequestParam String relativePath,@RequestParam String userId) {
        ResultUtil.checkParamIsNull(relativePath,userId);
        return fileService.publicViewFile(relativePath, userId);
    }

    @ApiOperation("分享：预览文件")
    @GetMapping("/public/s/preview/{fileId}/{shareId}")
    public String publicPreview(@PathVariable String fileId, @PathVariable String shareId) {
        boolean whetherExpired = shareService.checkWhetherExpired(shareId);
        if(whetherExpired){
            return fileService.viewFile(fileId, "preview");
        }
        return "forward:/public/s/invalid";
    }

    @ApiOperation("分享：下载单个文件")
    @GetMapping("/public/s/download/{fileId}/{shareId}")
    public String publicDownload(@PathVariable String fileId, @PathVariable String shareId) {
        boolean whetherExpired = shareService.checkWhetherExpired(shareId);
        if(whetherExpired){
            return fileService.viewFile(fileId, "download");
        }
        return "forward:/public/s/invalid";
    }

}