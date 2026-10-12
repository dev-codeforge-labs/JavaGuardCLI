import org.yaml.snakeyaml.Yaml;

class YamlUnsafe {
    Object load(String s) {
        return new Yaml().load(s);
    }
}
