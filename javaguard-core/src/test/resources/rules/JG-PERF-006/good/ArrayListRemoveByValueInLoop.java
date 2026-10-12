import java.util.ArrayList;
import java.util.List;

class ArrayListRemoveByValueInLoop {
    void removeAllBlank(ArrayList<String> items, List<String> blanks) {
        for (String blank : blanks) {
            items.remove(blank);
        }
    }
}
