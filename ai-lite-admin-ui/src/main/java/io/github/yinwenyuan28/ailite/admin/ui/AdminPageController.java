package io.github.yinwenyuan28.ailite.admin.ui;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 自带管理页静态资源。仅在 {@code ai-lite-admin-ui} 引入且 page-enabled 打开时注册。
 */
@RestController
public class AdminPageController {

    /**
     * @return 管理页 HTML
     */
    @GetMapping(value = "/ai-lite/admin", produces = "text/html")
    public ResponseEntity<byte[]> page() {
        return staticFile("index.html", "text/html;charset=UTF-8");
    }

    /**
     * @param name 只允许 admin.js / admin.css
     * @return 静态资源
     */
    @GetMapping("/ai-lite/admin/assets/{name:.+}")
    public ResponseEntity<byte[]> asset(@PathVariable("name") String name) {
        if ("admin.js".equals(name)) {
            return staticFile("admin.js", "application/javascript;charset=UTF-8");
        }
        if ("admin.css".equals(name)) {
            return staticFile("admin.css", "text/css;charset=UTF-8");
        }
        return ResponseEntity.notFound().build();
    }

    private static ResponseEntity<byte[]> staticFile(String name, String contentType) {
        ClassPathResource resource = new ClassPathResource("ai-lite-admin/" + name);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try {
            InputStream input = resource.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int n;
                while ((n = input.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
                return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType)).body(out.toByteArray());
            } finally {
                input.close();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("管理页资源读取失败");
        }
    }
}
