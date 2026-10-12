import java.util.ArrayDeque;

class ArrayDequeRemoveFirstInLoop {
    void drain(ArrayDeque<String> queue) {
        while (!queue.isEmpty()) {
            String head = queue.removeFirst();
            System.out.println(head);
        }
    }
}
