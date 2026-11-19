package io.github.vvb2060.packageinstaller.datamodel;

/**
 * 下载、探测、检查更新统一使用的 User-Agent。
 *
 * <p>不少 CDN / 对象存储 / 反爬网关会拒绝非浏览器 UA（返回 403 或干脆断开），
 * 所以对外请求一律伪装成 Android Chrome。格式取 Chrome 110+ 的简化型
 * （Android 版本号收敛为 {@code Android 10; K}），熵最低、最不容易被当成异常指纹。
 */
public final class UserAgent {
    public static final String CHROME_ANDROID =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/131.0.0.0 Mobile Safari/537.36";

    private UserAgent() {
    }
}
