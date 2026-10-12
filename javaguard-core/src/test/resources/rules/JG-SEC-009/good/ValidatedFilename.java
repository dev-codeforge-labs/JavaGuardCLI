import java.io.File;

class ValidatedFilename {
    File load(@org.springframework.web.bind.annotation.RequestParam String filename) {
        String safe = filename.replaceAll("[^A-Za-z0-9._-]", "");
        return new File(safe);
    }
}
