package com.jmal.clouddisk.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.PathUtil;
import cn.hutool.crypto.SecureUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.jmal.clouddisk.config.FileProperties;
import com.jmal.clouddisk.exception.CommonException;
import com.jmal.clouddisk.exception.ExceptionType;
import com.jmal.clouddisk.model.FileDocument;
import com.jmal.clouddisk.model.UploadApiParamDTO;
import com.jmal.clouddisk.model.UploadResponse;
import com.jmal.clouddisk.oss.web.WebOssService;
import com.jmal.clouddisk.util.CaffeineUtil;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.plexus.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author jmal
 * @Description 分片上传
 * @date 2023/4/7 17:20
 */
@Service
@Slf4j
public class MultipartUpload {

    @Autowired
    private FileProperties fileProperties;

    @Autowired
    private CommonFileService commonFileService;

    @Autowired
    private WebOssService webOssService;

    /***
     * 断点恢复上传缓存(已上传的缓存)
     */
    private static final Cache<String, CopyOnWriteArrayList<Integer>> resumeCache = CaffeineUtil.getResumeCache();
    /***
     * 上传大文件是需要分片上传，再合并
     * 已写入(合并)的分片缓存
     */
    private static final Cache<String, CopyOnWriteArrayList<Integer>> writtenCache = CaffeineUtil.getWrittenCache();
    /***
     * 未写入(合并)的分片缓存
     */
    private static final Cache<String, CopyOnWriteArrayList<Integer>> unWrittenCache = CaffeineUtil.getUnWrittenCacheCache();
    /***
     * 合并文件的写入锁缓存
     */
    private static final Cache<String, Lock> chunkWriteLockCache = CaffeineUtil.getChunkWriteLockCache();

    private String getUploadScope(UploadApiParamDTO upload) {
        if (Boolean.TRUE.equals(upload.getIsTogether())) {
            return fileProperties.getTogetherFileDir();
        }
        if (StringUtils.isNotEmpty(upload.getSpaceFlag())) {
            return upload.getSpaceFlag();
        }
        return upload.getUsername();
    }

    /**
     * 上传会话缓存键，避免同一 md5 在不同空间/目录并发上传时缓存串档。
     */
    private String getUploadCacheKey(UploadApiParamDTO upload) {
        return getUploadScope(upload)
                + "|"
                + Convert.toStr(upload.getCurrentDirectory(), "")
                + "|"
                + Convert.toStr(upload.getRelativePath(), "")
                + "|"
                + Convert.toStr(upload.getFilename(), "")
                + "|"
                + Convert.toStr(upload.getIdentifier(), "");
    }

    /**
     * 分片临时标识，既保留 md5 可读性，也附加会话维度隔离。
     */
    private String getUploadTempId(UploadApiParamDTO upload) {
        String cacheKey = getUploadCacheKey(upload);
        String cacheDigest = SecureUtil.sha256(cacheKey).substring(0, 24);
        return upload.getIdentifier() + "_" + cacheDigest;
    }

    /**
     * 上传分片文件
     * @param upload UploadApiParamDTO
     * @param uploadResponse UploadResponse
     * @param md5 md5
     * @param file MultipartFile
     */
    public void uploadChunkFile(UploadApiParamDTO upload, UploadResponse uploadResponse, String md5, MultipartFile file) throws IOException {
        // 多个分片
        // 落地保存文件
        // 这时保存的每个块, 块先存好, 后续会调合并接口, 将所有块合成一个大文件
        // 保存在用户的tmp目录下
        String uploadTempId = getUploadTempId(upload);
        File chunkFile = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            chunkFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), fileProperties.getTogetherFileDir(), uploadTempId, Convert.toStr(upload.getChunkNumber())).toFile();
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            chunkFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getSpaceFlag(), uploadTempId, Convert.toStr(upload.getChunkNumber())).toFile();
        }else{
            chunkFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getUsername(), uploadTempId, Convert.toStr(upload.getChunkNumber())).toFile();
        }
        try (java.io.InputStream inputStream = file.getInputStream()) {
            FileUtil.writeFromStream(inputStream, chunkFile);
        }
        setResumeCache(upload);
        uploadResponse.setUpload(true);
        // 追加分片
        appendChunkFile(upload);
        // 检测是否已经上传完了所有分片,上传完了则需要合并
        if (checkIsNeedMerge(upload)) {
            uploadResponse.setMerge(true);
        }
    }

    /**
     * 合并文件
     *
     * @param upload UploadApiParamDTO
     */
    public UploadResponse mergeFile(UploadApiParamDTO upload) throws IOException {
        UploadResponse uploadResponse = new UploadResponse();

        Path prePth = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            prePth = Paths.get(fileProperties.getTogetherFileDir(), upload.getCurrentDirectory(), upload.getFilename());
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            prePth = Paths.get(upload.getSpaceFlag(), upload.getCurrentDirectory(), upload.getFilename());
        }else{
            prePth = Paths.get(upload.getUsername(), upload.getCurrentDirectory(), upload.getFilename());
        }

        String ossPath = CaffeineUtil.getOssPath(prePth);
        if (ossPath != null) {
            return webOssService.mergeFile(ossPath, prePth, upload);
        }

        String md5 = upload.getIdentifier();
        String uploadCacheKey = getUploadCacheKey(upload);
        String uploadTempId = getUploadTempId(upload);
        // 使用 md5 + "_" + filename 作为中间文件名，与 appendChunkFile/appendFile 保持一致
        String intermediateFileName = uploadTempId + "_" + upload.getFilename();
        Path file = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            file = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), fileProperties.getTogetherFileDir(), intermediateFileName);
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            file = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getSpaceFlag(), intermediateFileName);
        }else{
            file = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getUsername(), intermediateFileName);
        }
        Path outputFile = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            outputFile = Paths.get(fileProperties.getRootDir(), fileProperties.getTogetherFileDir(), commonFileService.getUserDirectoryFilePath(upload));
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            outputFile = Paths.get(fileProperties.getRootDir(), upload.getSpaceFlag(), commonFileService.getUserDirectoryFilePath(upload));
        }else{
            outputFile = Paths.get(fileProperties.getRootDir(), upload.getUsername(), commonFileService.getUserDirectoryFilePath(upload));
        }
        // 清除缓存
        resumeCache.invalidate(uploadCacheKey);
        writtenCache.invalidate(uploadCacheKey);
        unWrittenCache.invalidate(uploadCacheKey);
        chunkWriteLockCache.invalidate(uploadCacheKey);
        Path chunkDir = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            chunkDir = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), fileProperties.getTogetherFileDir(), uploadTempId);
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            chunkDir = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getSpaceFlag(), uploadTempId);
        }else{
            chunkDir = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getUsername(), uploadTempId);
        }
        PathUtil.del(chunkDir);
        if (!Files.exists(outputFile)) {
            Files.createFile(outputFile);
        }
        PathUtil.move(file, outputFile, true);
        uploadResponse.setUpload(true);
        commonFileService.createFile(upload.getUsername(), outputFile.toFile(), null, null, upload.getIsTogether(), upload.getSpaceFlag());
        return uploadResponse;
    }

    /***
     * 合并文件追加分片
     * @param upload UploadApiParamDTO
     */
    private void appendChunkFile(UploadApiParamDTO upload) {
        int chunkNumber = upload.getChunkNumber();
        String md5 = upload.getIdentifier();
        String uploadCacheKey = getUploadCacheKey(upload);
        String uploadTempId = getUploadTempId(upload);
        // 未写入的分片
        CopyOnWriteArrayList<Integer> unWrittenChunks = unWrittenCache.get(uploadCacheKey, key -> new CopyOnWriteArrayList<>());
        if (unWrittenChunks != null && !unWrittenChunks.contains(chunkNumber)) {
            unWrittenChunks.add(chunkNumber);
            unWrittenCache.put(uploadCacheKey, unWrittenChunks);
        }
        // 已写入的分片
        CopyOnWriteArrayList<Integer> writtenChunks = writtenCache.get(uploadCacheKey, key -> new CopyOnWriteArrayList<>());
        // 使用 md5 + "_" + filename 作为中间文件名，避免不同子目录下同名文件的中间拼装文件路径冲突
        String intermediateFileName = uploadTempId + "_" + upload.getFilename();
        Path filePath = null;

        if (Boolean.TRUE.equals(upload.getIsTogether())){
            filePath = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), fileProperties.getTogetherFileDir(), intermediateFileName);
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            filePath = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getSpaceFlag(), intermediateFileName);
        }else{
            filePath = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getUsername(), intermediateFileName);
        }

        if (unWrittenChunks == null || writtenChunks == null) {
            return;
        }
        Lock lock = chunkWriteLockCache.get(uploadCacheKey, key -> new ReentrantLock());
        if (lock != null) {
            lock.lock();
        }
        try {
            if (Files.exists(filePath) && !writtenChunks.isEmpty()) {
                // 继续追加
                unWrittenChunks.forEach(unWrittenChunk -> appendFile(upload, unWrittenChunks, writtenChunks));
            } else {
                // 首次写入
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                }
                appendFile(upload, unWrittenChunks, writtenChunks);
            }
        } catch (Exception e) {
            throw new CommonException(ExceptionType.FAIL_MERGE_FILE);
        } finally {
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    public UploadResponse checkChunk(UploadApiParamDTO upload) throws IOException {
        Path prePth = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            prePth = Paths.get(fileProperties.getTogetherFileDir(), upload.getCurrentDirectory(), upload.getFilename());
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            prePth = Paths.get(upload.getSpaceFlag(), upload.getCurrentDirectory(), upload.getFilename());
        }else{
            prePth = Paths.get(upload.getUsername(), upload.getCurrentDirectory(), upload.getFilename());
        }
        String ossPath = CaffeineUtil.getOssPath(prePth);
        if (ossPath != null) {
            return webOssService.checkChunk(ossPath, prePth, upload);
        }

        UploadResponse uploadResponse = new UploadResponse();
        String md5 = upload.getIdentifier();
        String uploadCacheKey = getUploadCacheKey(upload);
        String path = commonFileService.getUserDirectory(upload.getCurrentDirectory());

        String relativePath = upload.getRelativePath();
        path += relativePath.substring(0, relativePath.length() - upload.getFilename().length());
        FileDocument fileDocument = commonFileService.getByMd5(path, upload.getUserId(), md5, upload.getIsTogether(), upload.getSpaceFlag());
        if (fileDocument != null) {
            // 文件已存在
            uploadResponse.setPass(true);
        } else {
            int totalChunks = upload.getTotalChunks();
            List<Integer> chunks = resumeCache.get(uploadCacheKey, key -> createResumeCache(upload));
            // 返回已存在的分片
            uploadResponse.setResume(chunks);
            assert chunks != null;
            if (totalChunks == chunks.size()) {
                // 文件不存在,并且已经上传了所有的分片,则合并保存文件
                mergeFile(upload);
            }
        }
        uploadResponse.setUpload(true);
        return uploadResponse;
    }

    /***
     * 追加分片操作
     * @param upload UploadApiParamDTO
     * @param unWrittenChunks  未写入的分片集合
     * @param writtenChunks    已写入的分片集合
     */
    private void appendFile(UploadApiParamDTO upload, CopyOnWriteArrayList<Integer> unWrittenChunks, CopyOnWriteArrayList<Integer> writtenChunks) {
        // 需要继续追加分片索引
        int chunk = 1;
        if (!writtenChunks.isEmpty()) {
            chunk = writtenChunks.get(writtenChunks.size() - 1) + 1;
        }
        if (!unWrittenChunks.contains(chunk)) {
            return;
        }
        String md5 = upload.getIdentifier();
        String uploadCacheKey = getUploadCacheKey(upload);
        String uploadTempId = getUploadTempId(upload);
        // 分片文件
        File chunkFile = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            chunkFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), fileProperties.getTogetherFileDir(), uploadTempId, String.valueOf(chunk)).toFile();
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            chunkFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getSpaceFlag(), uploadTempId, String.valueOf(chunk)).toFile();
        }else{
            chunkFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getUsername(), uploadTempId, String.valueOf(chunk)).toFile();
        }

        // 目标文件(使用 md5 + "_" + filename 作为中间文件名，避免不同子目录下同名文件冲突)
        String intermediateFileName = uploadTempId + "_" + upload.getFilename();
        File outputFile = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            outputFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), fileProperties.getTogetherFileDir(), intermediateFileName).toFile();
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            outputFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getSpaceFlag(), intermediateFileName).toFile();
        }else{
            outputFile = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getUsername(), intermediateFileName).toFile();
        }

        long position = outputFile.length();
        // 使用FileChannel.transferFrom零拷贝，避免将整个分片读入堆内存
        try (FileInputStream fis = new FileInputStream(chunkFile);
             FileChannel inChannel = fis.getChannel();
             FileOutputStream fileOutputStream = new FileOutputStream(outputFile, true);
             FileChannel outChannel = fileOutputStream.getChannel()) {
            long chunkSize = chunkFile.length();
            long transferred = outChannel.transferFrom(inChannel, position, chunkSize);
            if (transferred != chunkSize) {
                log.error("transferLength: {}, chunkFileLength: {}", transferred, chunkSize);
            }
            writtenChunks.add(chunk);
            writtenCache.put(uploadCacheKey, writtenChunks);
            unWrittenChunks.remove((Integer) chunk);
            unWrittenCache.put(uploadCacheKey, unWrittenChunks);
        } catch (IOException e) {
            throw new CommonException(ExceptionType.FAIL_MERGE_FILE);
        }
    }

    /***
     * 缓存已上传的分片
     * @param upload UploadApiParamDTO
     */
    private void setResumeCache(UploadApiParamDTO upload) {
        int chunkNumber = upload.getChunkNumber();
        String uploadCacheKey = getUploadCacheKey(upload);
        CopyOnWriteArrayList<Integer> chunks = resumeCache.get(uploadCacheKey, key -> createResumeCache(upload));
        assert chunks != null;
        if (!chunks.contains(chunkNumber)) {
            chunks.add(chunkNumber);
            resumeCache.put(uploadCacheKey, chunks);
        }
    }

    /***
     * 获取已经保存的分片索引
     */
    private CopyOnWriteArrayList<Integer> getSavedChunk(UploadApiParamDTO upload) {
        String uploadCacheKey = getUploadCacheKey(upload);
        return resumeCache.get(uploadCacheKey, key -> createResumeCache(upload));
    }

    /***
     * 检测是否需要合并
     */
    private boolean checkIsNeedMerge(UploadApiParamDTO upload) {
        int totalChunks = upload.getTotalChunks();
        CopyOnWriteArrayList<Integer> chunkList = getSavedChunk(upload);
        return totalChunks == chunkList.size();
    }

    /***
     * 读取分片文件是否存在
     * @return 已经保存的分片索引列表
     */
    private CopyOnWriteArrayList<Integer> createResumeCache(UploadApiParamDTO upload) {
        CopyOnWriteArrayList<Integer> resumeList = new CopyOnWriteArrayList<>();
        String uploadTempId = getUploadTempId(upload);
        // 读取tmp分片目录所有文件
        File f = null;
        if (Boolean.TRUE.equals(upload.getIsTogether())){
            f = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), fileProperties.getTogetherFileDir(), uploadTempId).toFile();
        }else if (StringUtils.isNotEmpty(upload.getSpaceFlag())){
            f = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getSpaceFlag(), uploadTempId).toFile();
        }else{
            f = Paths.get(fileProperties.getRootDir(), fileProperties.getChunkFileDir(), upload.getUsername(), uploadTempId).toFile();
        }
        if (f.exists()) {
            // 排除目录，只要文件
            File[] fileArray = f.listFiles(pathName -> !pathName.isDirectory());
            if (fileArray != null) {
                for (File file : fileArray) {
                    // 分片文件
                    int resume = Integer.parseInt(file.getName());
                    resumeList.add(resume);
                }
            }
        }
        return resumeList;
    }


}
