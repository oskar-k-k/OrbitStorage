package com.orbitstorage.file;

import java.io.IOException;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.orbitstorage.auth.Access;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;
    public FileController(FileService files) { this.files=files; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary="Upload raw file bytes",description="Requires a positive Content-Length, X-Filename and an allowed Content-Type. Multipart and chunked uploads are not supported.",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(content=@Content(mediaType="application/octet-stream",schema=@Schema(type="string",format="binary"))))
    public FileRecord.Metadata upload(
        @RequestHeader("X-Filename") String filename, @RequestHeader(value="Content-Type",required=false) String type,
        @Parameter(hidden=true) HttpServletRequest request) throws IOException {
        UUID projectId=Access.project("files:write");
        return files.upload(projectId,filename,type,request.getContentLengthLong(),request.getInputStream());
    }
    @GetMapping("/{fileId}/metadata") public FileRecord.Metadata metadata(@PathVariable UUID fileId) {
        UUID projectId=Access.project("files:read"); return files.get(projectId,fileId).metadata();
    }
    @GetMapping("/{fileId}") public void download(@PathVariable UUID fileId,HttpServletResponse response) throws IOException {
        UUID projectId=Access.project("files:read"); var file=files.get(projectId,fileId);
        long observed=0;
        try(var source=files.download(file)) {
            response.setContentType(file.contentType); response.setContentLengthLong(file.actualSize);
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(file.originalName,StandardCharsets.UTF_8).build().toString());
            response.setHeader("X-Content-Type-Options","nosniff"); response.setHeader("Cache-Control","private, no-store");
            var output=response.getOutputStream(); byte[] buffer=new byte[65536]; int read;
            while(observed<file.actualSize && (read=source.read(buffer,0,(int)Math.min(buffer.length,file.actualSize-observed)))!=-1) {
                output.write(buffer,0,read); observed+=read;
            }
            if(observed!=file.actualSize) throw new IOException("Stored object is incomplete");
        } finally { if(observed>0) files.downloaded(projectId,observed); }
    }
    @DeleteMapping("/{fileId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID fileId) { UUID projectId=Access.project("files:delete"); files.delete(projectId,fileId); }
}
