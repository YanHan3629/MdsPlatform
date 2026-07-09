package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.file.BeginFileWriteReq;
import com.fwdrobo.sirius.dto.file.BeginFileWriteResp;
import com.fwdrobo.sirius.dto.file.DownloadFileResp;
import com.fwdrobo.sirius.dto.file.FileStream;
import com.fwdrobo.sirius.dto.file.ListFilesQuery;
import com.fwdrobo.sirius.dto.file.ListFilesResp;
import com.fwdrobo.sirius.dto.file.ListGetResp;
import com.fwdrobo.sirius.dto.file.PresignGetResp;
import com.fwdrobo.sirius.dto.file.PresignPutResp;
import com.fwdrobo.sirius.dto.file.UploadFileResp;
import com.fwdrobo.sirius.service.FileService;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.PathUtils;
import com.fwdrobo.sirius.validation.ValidLogicalPath;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/artifacts/{artifactId}")  // 区分api路径
@Validated
@Slf4j
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * PUT /api/artifacts/{artifactId}/commits/{commitId}/files/deprecated
     * 测试接口：为指定路径生成预签名 PUT URL（上传地址）和 objectKey，客户端用该 URL 直传对象存储
     */
    @PutMapping("/commits/{commitId:(?!refs$).*}/files/deprecated")
    public BeginFileWriteResp beginWrite(
            @PathVariable UUID artifactId,
            @PathVariable UUID commitId,
            @Valid @RequestBody BeginFileWriteReq req,
            @RequestParam(required = false) UUID deviceId
    ) throws Exception {
        log.debug("beginWrite request: artifactId={}, commitId={}, path={}, contentType={}, expireSeconds={}, deviceId={}",
                artifactId, commitId, req.getPath(), req.getContentType(), req.getExpireSeconds(), deviceId);
        PresignPutResp resp = fileService.beginWrite(
                artifactId,
                commitId,
                req.getPath(),
                req.getContentType(),
                Duration.ofSeconds(req.getExpireSeconds()),
                deviceId
        );
        String logicalPath = req.getPath().startsWith("/") ? req.getPath() : "/" + req.getPath();
        return new BeginFileWriteResp(logicalPath, resp.getObjectKey(), resp.getUrl(), null, "PUT");
    }

    /**
     * PUT /api/artifacts/{artifactId}/commits/{commitId}/files?path=xxx
     * 文件上传,通过后端转发到 MinIO（写入指定路径）
     */
    @PutMapping("/commits/{commitId:(?!refs$).*}/files")
    public UploadFileResp uploadContent(
            @PathVariable UUID artifactId,
            @PathVariable UUID commitId,
            @RequestParam
            @ValidLogicalPath
            String path,
            @RequestParam(required = false) String contentType,
            @RequestParam(required = false) UUID deviceId,
            HttpServletRequest request
    ) throws Exception {
        String resolvedType = (contentType == null || contentType.isBlank())
                ? request.getContentType()
                : contentType;
        long size = request.getContentLengthLong();
        log.debug("uploadContent request: artifactId={}, commitId={}, path={}, contentType={}, size={}, deviceId={}",
                artifactId, commitId, path, resolvedType, size, deviceId);
        try (var inputStream = request.getInputStream()) {
            return fileService.uploadContent(
                    artifactId,
                    commitId,
                    path,
                    resolvedType,
                    inputStream,
                    size,
                    deviceId
            );
        }
    }

    /**
     * DELETE /api/artifacts/{artifactId}/commits/{commitId}/files?path=xxx
     * 删除指定路径的文件（从该 commit 中移除）。
     */
    @DeleteMapping("/commits/{commitId:(?!refs$).*}/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID artifactId,
            @PathVariable UUID commitId,
            @RequestParam
            @ValidLogicalPath
            String path
    ) throws Exception {
        log.debug("delete request: artifactId={}, commitId={}, path={}", artifactId, commitId, path);
        fileService.deleteFile(artifactId, commitId, path);
    }

    /**
     * GET /api/artifacts/{artifactId}/commits/{commitId}/files/content?path=xxx
     * GET /api/artifacts/{artifactId}/commits/{commitId}/files/content/{*path}
     * 服务端转发下载内容（指定路径，支持 query 和 path 两种传参方式）。
     */
    @GetMapping({
            "/commits/{commitId:(?!refs$).*}/files/content",
            "/commits/{commitId:(?!refs$).*}/files/content/{*pathInUri}"
    })
    public void downloadContent(
            @PathVariable UUID artifactId,
            @PathVariable UUID commitId,
            @RequestParam(required = false) String path,
            @PathVariable(name = "pathInUri", required = false) String pathInUri,
            HttpServletResponse response
    ) throws Exception {
        String resolvedPath = resolveDownloadPath(path, pathInUri);
        log.debug("downloadContent request: artifactId={}, commitId={}, path={}", artifactId, commitId, resolvedPath);
        FileStream stream = fileService.downloadContent(artifactId, commitId, resolvedPath);
        writeFileStreamToResponse(response, stream);
    }

    /**
     * GET /api/artifacts/{artifactId}/commits/{commitId}/files/{fileId}/content
     * 服务端转发下载内容（指定 fileId）。
     */
    @GetMapping("/commits/{commitId:(?!refs$).*}/files/{fileId}/content")
    public void downloadContentById(
            @PathVariable UUID artifactId,
            @PathVariable UUID commitId,
            @PathVariable UUID fileId,
            HttpServletResponse response
    ) throws Exception {
        log.debug("downloadContentById request: artifactId={}, commitId={}, fileId={}", artifactId, commitId, fileId);
        FileStream stream = fileService.downloadContentById(artifactId, commitId, fileId);
        writeFileStreamToResponse(response, stream);
    }

    /**
     * GET /api/artifacts/{artifactId}/commits/{commitId}/files/deprecated?path=xxx&expireSeconds=60
     * 为指定路径生成预签名 GET URL（下载地址），有效期为 expireSeconds，客户端用 URL 直下对象存储。
     */
    @GetMapping("/commits/{commitId:(?!refs$).*}/files/deprecated")
    public DownloadFileResp download(
            @PathVariable UUID artifactId,
            @PathVariable UUID commitId,
            @RequestParam
            @ValidLogicalPath
            String path,
            @RequestParam
            @Min(value = 1, message = "expireSeconds 范围: [1, 43200]")
            @Max(value = 12 * 60 * 60, message = "expireSeconds 范围: [1, 43200]")
            int expireSeconds
    ) throws Exception {
        log.debug("download request: artifactId={}, commitId={}, path={}, expireSeconds={}",
                artifactId, commitId, path, expireSeconds);
        PresignGetResp resp = fileService.downloadUrl(
                artifactId,
                commitId,
                path,
                Duration.ofSeconds(expireSeconds)
        );
        String logicalPath = path.startsWith("/") ? path : "/" + path;
        return new DownloadFileResp(logicalPath, resp.getObjectKey(), resp.getUrl(), "GET");
    }

    /**
     * GET /api/artifacts/{artifactId}/commits/{commitId}/files?glob=/*.mp4&pageIdx=1&pageSize=100
     * 列出该 commit 下所有文件路径(按 path 排序)；可选 glob 过滤与分页（后续可扩展 withStats/diff 等）。
     */
    @GetMapping("/commits/{commitId:(?!refs$).*}/files")
    public ListFilesResp listFiles(
            @PathVariable UUID artifactId,
            @PathVariable UUID commitId,
            @RequestParam(required = false) String glob,
            @Min(value = 1, message = "pageIdx从1开始") @RequestParam(required = false) Integer pageIdx,
            @Max(value = 1000, message = "pageSize最大限制为1000") @RequestParam(required = false) Integer pageSize
            // TODO:按需求添加其他过滤参数
    ) throws Exception {
        log.debug("listFiles request: artifactId={}, commitId={}, glob={}, pageIdx={}, pageSize={}",
                artifactId, commitId, glob, pageIdx, pageSize);
        ListGetResp listGetResp = fileService.listFiles(
                ListFilesQuery.builder()
                        .repoId(artifactId)
                        .commitId(commitId)
                        .globPattern(glob)
                        .pageIdx(pageIdx)
                        .pageSize(pageSize)
                        .build()
        );
        return listGetResp.toListFilesResp();
    }

    /**
     * 统一处理流式下载响应头与内容（公共方法，可被其他 Controller 调用）
     */
    public static void writeFileStreamToResponse(HttpServletResponse response, FileStream stream) throws IOException {
        String contentType = stream.contentType();
        if (contentType != null && !contentType.isBlank()) {
            response.setContentType(contentType);
        }
        if (stream.size() >= 0) {
            response.setContentLengthLong(stream.size());
        }
        try (InputStream in = stream.inputStream(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /**
     * 统一解析下载接口中的路径参数，兼容 query/path 两种传递方式。
     */
    private String resolveDownloadPath(String queryPath, String pathInUri) {
        boolean hasQueryPath = queryPath != null && !queryPath.isBlank();
        boolean hasPathInUri = pathInUri != null && !pathInUri.isBlank();
        if (hasQueryPath == hasPathInUri) {
            throw ExceptionUtils.badRequest("path参数必须且只能通过一种方式传递");
        }
        String rawPath = hasQueryPath ? queryPath : (pathInUri.startsWith("/") ? pathInUri : "/" + pathInUri);
        return PathUtils.normalizePath(rawPath);
    }
}
