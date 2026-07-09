package com.fwdrobo.sirius.util;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class ScriptUtils {

    /**
     * 将 scriptDir 下所有常规文件打成 tar.gz，并且 tar 内部扁平化（只保留文件名，不保留目录结构）。
     * 如有同名文件，会自动追加 -1/-2... 避免 tar entry 重名覆盖。
     */
    public static void writeScriptsTarGzFlat(Path scriptDir, OutputStream out) throws IOException {
        if (scriptDir == null) throw new IllegalArgumentException("scriptDir is null");
        if (!Files.exists(scriptDir)) throw new NoSuchFileException("scriptDir not exists: " + scriptDir);
        if (!Files.isDirectory(scriptDir))
            throw new NotDirectoryException("scriptDir is not a directory: " + scriptDir);

        // 外层尽量别 close 掉 servlet 的 OutputStream，所以只 close 我们包起来的包装流
        try (BufferedOutputStream bos = new BufferedOutputStream(out);
             GZIPOutputStream gzos = new GZIPOutputStream(bos);
             TarArchiveOutputStream taos = new TarArchiveOutputStream(gzos)) {

            // tar 里必须用 long file mode，否则文件名长一点会出问题
            taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            Map<String, Integer> nameCount = new HashMap<>();

            // 遍历目录（递归）
            try (var stream = Files.walk(scriptDir)) {
                stream
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .forEach(p -> {
                            try {
                                String entryName = makeUniqueFlatName(p.getFileName().toString(), nameCount);

                                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                                entry.setSize(Files.size(p));
                                // 可选：保留可执行位（如果你需要脚本可执行）
                                // entry.setMode(0755);

                                taos.putArchiveEntry(entry);
                                try (InputStream in = Files.newInputStream(p)) {
                                    IOUtils.copy(in, taos);
                                }
                                taos.closeArchiveEntry();
                            } catch (IOException e) {
                                // 把 IOException 转成 unchecked，外层再统一处理
                                throw new RuntimeException("Failed to add file to tar: " + p, e);
                            }
                        });
            } catch (RuntimeException re) {
                // unwrap
                if (re.getCause() instanceof IOException ioe) throw ioe;
                throw re;
            }

            taos.finish();
            taos.flush();
        }
    }

    private static String makeUniqueFlatName(String baseName, Map<String, Integer> nameCount) {
        int n = nameCount.getOrDefault(baseName, 0);
        nameCount.put(baseName, n + 1);

        if (n == 0) return baseName;

        // 给重复文件名加后缀：a.sh -> a-1.sh
        int dot = baseName.lastIndexOf('.');
        if (dot > 0 && dot < baseName.length() - 1) {
            String stem = baseName.substring(0, dot);
            String ext = baseName.substring(dot); // includes '.'
            return stem + "-" + n + ext;
        } else {
            return baseName + "-" + n;
        }
    }
}
