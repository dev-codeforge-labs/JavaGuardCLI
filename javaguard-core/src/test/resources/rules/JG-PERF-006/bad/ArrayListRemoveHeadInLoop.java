import java.util.ArrayList;

class ArrayListRemoveHeadInLoop {
    void drain(ArrayList<String> queue) {
        while (!queue.isEmpty()) {
            String head = queue.remove(0);
            System.out.println(head);
        }
    }
}
