import java.io.File;

class FileFromRequestParam {
    File load(@org.springframework.web.bind.annotation.RequestParam String filename) {
        return new File(filename);
    }
}
