import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class YamlSafe {
    Object load(String s) {
        return new Yaml(new SafeConstructor()).load(s);
    }
}
