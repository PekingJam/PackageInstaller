package io.github.vvb2060.packageinstaller.apk;

import android.content.pm.PackageInstaller;
import android.content.res.ApkAssets;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager$Builder;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.Log;


import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.github.vvb2060.packageinstaller.apksig.Parser;
import io.github.vvb2060.packageinstaller.datamodel.DataSource;
import io.github.vvb2060.packageinstaller.datamodel.FileDataSource;
import io.github.vvb2060.packageinstaller.datamodel.OutputStreamDataSink;
import io.github.vvb2060.packageinstaller.zip.CentralDirectoryRecord;
import io.github.vvb2060.packageinstaller.zip.LocalFileRecord;
import io.github.vvb2060.packageinstaller.zip.ZipFormatException;
import io.github.vvb2060.packageinstaller.zip.ZipUtils;

public class ZipParser {
    private static final String TAG = ZipParser.class.getSimpleName();

    private final List<CentralDirectoryRecord> cdRecords;
    private final DataSource dataSource;
    private final long cdStartOffset;

    private List<CentralDirectoryRecord> apksRecords;
    private String installName;

    public ZipParser(DataSource zip) throws IOException {
        dataSource = zip;
        try {
            var zipSections = ZipUtils.findZipSections(zip);
            cdRecords = ZipUtils.parseZipCentralDirectory(zip, zipSections);
            cdStartOffset = zipSections.getZipCentralDirectoryOffset();
        } catch (ZipFormatException e) {
            throw new IOException(e);
        }
    }

    public ApkLite parse() throws IOException {
        return parse(false);
    }

    private ApkLite parse(boolean isApk) throws IOException {
        try {
            var apkLite = parseAsApk();
            if (apkLite != null) {
                try {
                    apkLite.signingBlock = Parser.perse(dataSource);
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "Failed to parse signing block", e);
                }
                if (apkLite.splitName != null) {
                    installName = "split_" + apkLite.splitName + ".apk";
                } else {
                    installName = "base.apk";
                }
                apkLite.size = dataSource.size();
                return apkLite;
            } else if (isApk) {
                throw new IOException("Not a valid apk");
            }
            return findApkFromZip();
        } catch (ZipFormatException | RuntimeException e) {
            throw new IOException(e);
        }
    }

    private ApkLite parseAsApk() throws IOException, ZipFormatException {
        for (int i = 0, cdRecordsSize = cdRecords.size(); i < cdRecordsSize; i++) {
            var cdRecord = cdRecords.get(i);
            if ("AndroidManifest.xml".equals(cdRecord.getName())) {
                if (dataSource instanceof FileDataSource file) {
                    try {
                        return getAppLiteFromFd(file.getAssetFileDescriptor());
                    } catch (IOException | RuntimeException | LinkageError e) {
                        // LinkageError：隐藏 API 被拦（NoSuchMethodError 等）。不能让它冒泡，
                        // 回退到直接读 AndroidManifest.xml 的路径。
                        Log.w(TAG, "Failed to parse from file fd, falling back", e);
                    }
                }
                var xmlBytes = LocalFileRecord.getUncompressedData(
                    dataSource, cdRecord, cdStartOffset);
                return getAppLiteFromXmlBytes(xmlBytes);
            }
        }
        return null;
    }

    private ApkLite findApkFromZip() throws IOException, ZipFormatException {
        var entries = new LinkedHashMap<String, CentralDirectoryRecord>();
        var dir = "";
        var isApks = false;
        for (int i = 0, cdRecordsSize = cdRecords.size(); i < cdRecordsSize; i++) {
            var record = cdRecords.get(i);
            var name = record.getName();
            if (name.equals("toc.pb")) {
                isApks = true;
            }
            if (name.startsWith(dir) && name.endsWith(".apk")) {
                if (entries.isEmpty() && name.indexOf('/') != -1) {
                    dir = name.substring(0, name.lastIndexOf('/') + 1);
                }
                entries.putIfAbsent(name, record);
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("No apk found in the zip");
        }

        var base = findBaseApk(entries);
        var apk = LocalFileRecord.getRecord(dataSource, base, cdStartOffset);
        var offset = apk.getStartOffsetInArchive() + apk.getDataStartOffsetInRecord();
        ApkLite apkLite;
        var method = base.getCompressionMethod();
        if (method == ZipEntry.STORED) {
            var newZip = dataSource.slice(offset, base.getUncompressedSize());
            apkLite = new ZipParser(newZip).parse(true);
        } else if (method == ZipEntry.DEFLATED) {
            Log.w(TAG, "Parsing compressed apk: " + base.getName());
            var in = dataSource.openStream(offset, base.getCompressedSize());
            apkLite = parseApkFromStream(in);
        } else {
            throw new IOException("Unsupported compression method: " + method);
        }

        apkLite.zip = true;
        apksRecords = ApksFilter.filterZipEntries(entries.values(), isApks);
        apkLite.size = apksRecords.stream()
            .mapToLong(CentralDirectoryRecord::getCompressedSize)
            .sum();
        return apkLite;
    }

    public void sessionWrite(PackageInstaller.Session session,
                             Consumer<Integer> liveData) throws IOException {
        liveData.accept(0);
        if (installName != null) {
            long total = dataSource.size();
            var progress = new Progress(liveData, total);
            try (var out = session.openWrite(installName, 0, total)) {
                var sink = new OutputStreamDataSink(out, progress);
                dataSource.feed(0, total, sink);
                session.fsync(out);
            }
            return;
        }
        if (apksRecords != null) {
            sessionWriteApks(session, liveData);
        }
    }

    /** 待安装内容的总字节数，用于 pm install-create -S。 */
    public long totalSize() {
        if (installName != null) return dataSource.size();
        if (apksRecords != null) {
            return apksRecords.stream()
                .mapToLong(CentralDirectoryRecord::getUncompressedSize)
                .sum();
        }
        return 0;
    }

    public interface EntrySink {
        OutputStream open(String name, long size) throws IOException;
    }

    /** 与 sessionWrite 相同的遍历顺序，但把内容写到任意目标（Root 模式走 su 的 stdin）。 */
    public void writeTo(EntrySink target, Consumer<Integer> liveData) throws IOException {
        liveData.accept(0);
        if (installName != null) {
            long total = dataSource.size();
            var progress = new Progress(liveData, total);
            try (var out = target.open(installName, total)) {
                dataSource.feed(0, total, new OutputStreamDataSink(out, progress));
            }
            return;
        }
        if (apksRecords == null) return;

        var progress = new Progress(liveData, totalSize());
        for (int i = 0, size = apksRecords.size(); i < size; i++) {
            var record = apksRecords.get(i);
            var fileName = record.getName();
            int slash = fileName.lastIndexOf('/');
            if (slash >= 0) fileName = fileName.substring(slash + 1);
            try (var out = target.open(fileName, record.getUncompressedSize())) {
                var sink = new OutputStreamDataSink(out, progress);
                LocalFileRecord.outputUncompressedData(dataSource, record, cdStartOffset, sink);
            } catch (ZipFormatException e) {
                throw new IOException(e);
            }
        }
    }

    private void sessionWriteApks(PackageInstaller.Session session,
                                  Consumer<Integer> liveData) throws IOException {
        var total = apksRecords.stream()
            .mapToLong(CentralDirectoryRecord::getUncompressedSize)
            .sum();
        var progress = new Progress(liveData, total);
        for (int i = 0, apksRecordsSize = apksRecords.size(); i < apksRecordsSize; i++) {
            var record = apksRecords.get(i);
            var fileName = record.getName();
            int slash = fileName.lastIndexOf('/');
            if (slash >= 0) fileName = fileName.substring(slash + 1);
            try (var out = session.openWrite(fileName, 0, record.getUncompressedSize())) {
                var sink = new OutputStreamDataSink(out, progress);
                LocalFileRecord.outputUncompressedData(dataSource, record, cdStartOffset, sink);
                session.fsync(out);
            } catch (ZipFormatException e) {
                throw new IOException(e);
            }
        }
    }

    private static ApkLite parseApkFromStream(InputStream in) throws IOException {
        try (var raw = new InflaterInputStream(in, new Inflater(true), 16 * 1024)) {
            try (var zipInput = new ZipInputStream(raw)) {
                var innerEntry = zipInput.getNextEntry();
                while (innerEntry != null) {
                    if (!innerEntry.getName().equals("AndroidManifest.xml")) {
                        innerEntry = zipInput.getNextEntry();
                        continue;
                    }
                    var initCap = innerEntry.getSize();
                    if (initCap <= 0) initCap = 16 * 1024;
                    var output = new ByteArrayOutputStream((int) initCap);
                    var buffer = new byte[16 * 1024];
                    int numRead;
                    while ((numRead = zipInput.read(buffer)) != -1) {
                        output.write(buffer, 0, numRead);
                    }
                    return getAppLiteFromXmlBytes(output.toByteArray());
                }
                throw new IOException("Not a valid apk");
            }
        }
    }

    private static CentralDirectoryRecord findBaseApk(Map<String, CentralDirectoryRecord> entries) {
        if (entries.size() == 1) {
            return entries.values().iterator().next();
        }

        var base = entries.get("base.apk");
        if (base != null) return base;

        base = entries.get("splits/base-master.apk");
        if (base != null) return base;

        for (var entry : entries.values()) {
            var name = entry.getName();
            name = name.substring(0, name.length() - ".apk".length());
            if (name.startsWith("config.")) continue;
            if (name.indexOf('.') == -1) continue;
            return entry;
        }

        return entries.values().iterator().next();
    }

    private static ApkLite getAppLiteFromXmlBytes(byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try (var block = new android.content.res.XmlBlock(data);
                 var parser = block.newParser()) {
                var res = Resources.getSystem();
                return dropIcon(ApkLite.fromAndroidManifest(parser, res));
            } catch (XmlPullParserException | RuntimeException e) {
                throw new IOException("Failed to parse AndroidManifest.xml", e);
            }
        }

        try {
            var clazz = Class.forName("android.content.res.XmlBlock");
            var constructor = clazz.getConstructor(byte[].class);
            constructor.setAccessible(true);
            // noinspection DiscouragedPrivateApi
            var newParserMethod = clazz.getDeclaredMethod("newParser");
            try (var xmlBlockInstance = (AutoCloseable) constructor.newInstance((Object) data);
                 var parser = (XmlResourceParser) newParserMethod.invoke(xmlBlockInstance)) {
                var res = Resources.getSystem();
                assert parser != null;
                return dropIcon(ApkLite.fromAndroidManifest(parser, res));
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse AndroidManifest.xml", e);
        }
    }

    /**
     * 走 XmlBlock + 系统资源解析时，拿到的 icon 要么是 null、要么是系统的默认图标
     * （APK 自己的资源表没加载），不能当成应用图标用，交给上层显示占位图标。
     */
    private static ApkLite dropIcon(ApkLite apkLite) {
        apkLite.icon = null;
        return apkLite;
    }

    private static ApkLite getAppLiteFromFd(AssetFileDescriptor afd) throws IOException {
        var fd = afd.getFileDescriptor();
        var name = afd.toString();
        var offset = afd.getStartOffset();
        var length = afd.getLength();

        var assets = ApkAssets.loadFromFd(fd, name, offset, length, 0, null);
        try (var am = new AssetManager$Builder().addApkAssets(assets).build();
             var parser = assets.openXml("AndroidManifest.xml")) {
            var res = Resources.getSystem();
            var apkResources = new Resources(am, res.getDisplayMetrics(), res.getConfiguration());
            return ApkLite.fromAndroidManifest(parser, apkResources);
        } catch (XmlPullParserException e) {
            throw new IOException("Failed to parse AndroidManifest.xml", e);
        } finally {
            assets.close();
        }
    }

    private static class Progress implements Consumer<Integer> {
        private final Consumer<Integer> liveData;
        private final long total;
        private int lastProgress = 0;
        private long sum = 0;

        Progress(Consumer<Integer> liveData, long total) {
            this.liveData = liveData;
            this.total = total;
        }

        @Override
        public void accept(Integer i) {
            sum += i;
            int progress = (int) (sum * 100 / total);
            if (progress > lastProgress) {
                lastProgress = progress;
                liveData.accept(progress);
            }
        }
    }
}
