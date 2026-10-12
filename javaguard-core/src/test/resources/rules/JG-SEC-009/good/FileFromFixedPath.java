import java.io.File;

class FileFromFixedPath {
    File load(@org.springframework.web.bind.annotation.RequestParam String filename) {
        return new File("/var/data/uploads.log");
    }
}
